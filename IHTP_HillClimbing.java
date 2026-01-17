import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class IHTP_HillClimbing {

  // --- Inner Classes for State Representation ---

  static class OutputPatient {
    String id;
    int pIndex; // Index in IHTP_Input (for fast lookup)
    Integer assignedDay; // null if unassigned
    String assignedRoom;
    String assignedTheater;
    // Cache helpers
    int rIndex = -1;
    int tIndex = -1;

    public OutputPatient copy() {
      OutputPatient op = new OutputPatient();
      op.id = this.id;
      op.pIndex = this.pIndex;
      op.assignedDay = this.assignedDay;
      op.assignedRoom = this.assignedRoom;
      op.assignedTheater = this.assignedTheater;
      op.rIndex = this.rIndex;
      op.tIndex = this.tIndex;
      return op;
    }
  }

  static class OutputNurse {
    String id;
    int nIndex;
    // Map shift index (global) to list of rooms
    Map<Integer, List<String>> assignments = new HashMap<>();

    public OutputNurse copy() {
      OutputNurse on = new OutputNurse();
      on.id = this.id;
      on.nIndex = this.nIndex;
      for (Map.Entry<Integer, List<String>> entry : this.assignments.entrySet()) {
        on.assignments.put(entry.getKey(), new ArrayList<>(entry.getValue()));
      }
      return on;
    }
  }

  static class SolutionState {
    List<OutputPatient> patients = new ArrayList<>();
    List<OutputNurse> nurses = new ArrayList<>();

    // --- Helper Structures for O(1) Constraint Checking ---
    // Room usage: [room][day] -> List of Patient Indices
    List<Integer>[][] roomDayOccupancy;

    // Nurse Load: [nurse][shift] -> load
    int[][] nurseShiftLoad;

    // Nurse Location: [room][shift] -> nurseIndex (-1 if none)
    int[][] roomShiftNurse;

    // Surgeon Load: [surgeon][day] -> load (minutes)
    int[][] surgeonDayLoad;
    // OT Load: [theater][day] -> load (minutes)
    int[][] operatingTheaterDayLoad;

    double softCost = 0.0;
    int hardViolations = 0;

    @SuppressWarnings("unchecked")
    public SolutionState(int R, int D, int N, int S, int Surg, int T) {
      roomDayOccupancy = new ArrayList[R][D];
      for (int r = 0; r < R; r++)
        for (int d = 0; d < D; d++)
          roomDayOccupancy[r][d] = new ArrayList<>();

      nurseShiftLoad = new int[N][S]; // S is total shifts
      roomShiftNurse = new int[R][S];
      for (int r = 0; r < R; r++)
        Arrays.fill(roomShiftNurse[r], -1);

      surgeonDayLoad = new int[Surg][D];
      operatingTheaterDayLoad = new int[T][D];
    }

    // Deep Copy
    public SolutionState copy(int R, int D, int N, int S, int Surg, int T) {
      SolutionState s = new SolutionState(R, D, N, S, Surg, T);
      for (OutputPatient p : this.patients)
        s.patients.add(p.copy());
      for (OutputNurse n : this.nurses)
        s.nurses.add(n.copy());
      s.softCost = this.softCost;
      s.hardViolations = this.hardViolations;

      // Deep copy helpers
      for (int r = 0; r < R; r++)
        for (int d = 0; d < D; d++)
          s.roomDayOccupancy[r][d] = new ArrayList<>(this.roomDayOccupancy[r][d]);

      for (int n = 0; n < N; n++)
        s.nurseShiftLoad[n] = Arrays.copyOf(this.nurseShiftLoad[n], S);

      for (int r = 0; r < R; r++)
        s.roomShiftNurse[r] = Arrays.copyOf(this.roomShiftNurse[r], S);

      for (int surg = 0; surg < Surg; surg++)
        s.surgeonDayLoad[surg] = Arrays.copyOf(this.surgeonDayLoad[surg], D);

      for (int t = 0; t < T; t++)
        s.operatingTheaterDayLoad[t] = Arrays.copyOf(this.operatingTheaterDayLoad[t], D);

      return s;
    }
  }

  // --- Main Fields ---
  private IHTP_Input input;
  private SolutionState currentState;
  private SolutionState bestState;
  private Random rand = new Random(42); // Seed for reproducibility
  private int R, D, N, S, T, P;
  private String outputFilePath;

  // Soft Constraint Weights
  private int w_age, w_skill, w_cont, w_load, w_ot, w_trans, w_delay, w_opt;

  public IHTP_HillClimbing(String instanceFile, String solutionFile, String outputFilePath) throws IOException {
    this.outputFilePath = outputFilePath;
    // 1. Load Input
    this.input = new IHTP_Input(instanceFile);
    this.P = input.Patients();
    this.R = input.Rooms();
    this.D = input.Days();
    this.N = input.Nurses();
    this.S = input.Shifts(); // Total shifts (Days * ShiftsPerDay)
    this.T = input.OperatingTheaters();

    // Parse Weights
    JSONObject json = new JSONObject(new JSONTokener(new FileReader(instanceFile)));
    JSONObject weights = json.getJSONObject("weights");
    w_age = weights.getInt("room_mixed_age");
    w_skill = weights.getInt("room_nurse_skill");
    w_cont = weights.getInt("continuity_of_care");
    w_load = weights.getInt("nurse_eccessive_workload");
    w_ot = weights.getInt("open_operating_theater");
    w_trans = weights.getInt("surgeon_transfer");
    w_delay = weights.getInt("patient_delay");
    w_opt = weights.getInt("unscheduled_optional");

    // 2. Load Initial Solution
    loadSolution(solutionFile);

    // 3. Initialize Helper Structures
    rebuildHelpers(currentState);

    // 4. Initial Full Eval
    evaluateFull(currentState);

    this.bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);
  }

  // Hill Climbing Parameters
  private int timeLimitSeconds = 600;
  private int maxIterationsWithoutImprovement = 1000; // Stop if no improvement after this many iterations

  private void loadSolution(String solutionFile) throws IOException {
    this.currentState = new SolutionState(R, D, N, S, input.Surgeons(), T);
    JSONObject j_sol = new JSONObject(new JSONTokener(new FileReader(solutionFile)));

    // 1. Initialize Patients from Input (ensure index i == pIndex)
    for (int i = 0; i < P; i++) {
      OutputPatient op = new OutputPatient();
      op.id = input.PatientId(i);
      op.pIndex = i;
      op.assignedDay = null; // Default unassigned
      op.assignedRoom = null;
      op.assignedTheater = null;
      currentState.patients.add(op);
    }

    // 2. Update with JSON Data
    JSONArray j_pats = j_sol.getJSONArray("patients");
    for (int i = 0; i < j_pats.length(); i++) {
      JSONObject jp = j_pats.getJSONObject(i);
      String id = jp.getString("id");
      int pIndex = input.findPatientIndex(id);

      if (pIndex != -1 && pIndex < P) {
        OutputPatient op = currentState.patients.get(pIndex);
        if (!jp.isNull("admission_day") && !jp.get("admission_day").equals("none")) {
          op.assignedDay = jp.getInt("admission_day");
          op.assignedRoom = jp.isNull("room") ? null : jp.getString("room");
          op.assignedTheater = jp.isNull("operating_theater") ? null : jp.getString("operating_theater");
          op.rIndex = (op.assignedRoom != null) ? input.findRoomIndex(op.assignedRoom) : -1;
          op.tIndex = (op.assignedTheater != null) ? input.findOperatingTheaterIndex(op.assignedTheater) : -1;
        }
      }
    }

    // Parse Nurses
    JSONArray j_nurs = j_sol.getJSONArray("nurses");
    for (int i = 0; i < j_nurs.length(); i++) {
      JSONObject jn = j_nurs.getJSONObject(i);
      OutputNurse on = new OutputNurse();
      on.id = jn.getString("id");
      on.nIndex = input.findNurseIndex(on.id);
      JSONArray assigns = jn.getJSONArray("assignments");
      for (int k = 0; k < assigns.length(); k++) {
        JSONObject ja = assigns.getJSONObject(k);
        int day = ja.getInt("day");
        String shiftStr = ja.getString("shift");
        int shiftIdx = input.findShiftIndex(shiftStr);
        int shiftGlobal = day * input.ShiftsPerDay() + shiftIdx;
        List<String> rooms = new ArrayList<>();
        JSONArray jr = ja.getJSONArray("rooms");
        for (int m = 0; m < jr.length(); m++) {
          rooms.add(jr.getString(m));
        }
        on.assignments.put(shiftIdx, rooms);
      }
      currentState.nurses.add(on);
    }
    currentState.nurses.sort((a, b) -> Integer.compare(a.nIndex, b.nIndex));
  }

  private void rebuildHelpers(SolutionState state) {
    // 1. Room Occupancy
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int d = p.assignedDay; d < Math.min(D, p.assignedDay + len); d++) {
        if (p.rIndex >= 0 && p.rIndex < R && d < D)
          state.roomDayOccupancy[p.rIndex][d].add(p.pIndex);
      }
    }
    // Occupants (fixed)
    for (int occ = 0; occ < input.Occupants(); occ++) {
      int r = input.OccupantRoom(occ);
      int len = input.OccupantLengthOfStay(occ);
      for (int d = 0; d < Math.min(D, len); d++) {
        if (r >= 0 && r < R && d < D) {
          state.roomDayOccupancy[r][d].add(-(occ + 1)); // Negative to distinguish
        }
      }
    }

    // 2. Nurse Assignments
    for (OutputNurse n : state.nurses) {
      for (var entry : n.assignments.entrySet()) {
        int shiftIdx = entry.getKey();
        for (String room : entry.getValue()) {
          int rIdx = input.findRoomIndex(room);
          state.roomShiftNurse[rIdx][shiftIdx] = n.nIndex;
        }
      }
    }

    // 4. Surgeon & OT Loads
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      int surg = input.PatientSurgeon(p.pIndex);
      state.surgeonDayLoad[surg][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
      state.operatingTheaterDayLoad[p.tIndex][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
    }
  }

  // --- Evaluation ---
  public void evaluateFull(SolutionState state) {
    double cost = 0;
    int hard = 0;

    // Reset Loads
    for (int n = 0; n < N; n++)
      Arrays.fill(state.nurseShiftLoad[n], 0);
    for (int s = 0; s < input.Surgeons(); s++)
      Arrays.fill(state.surgeonDayLoad[s], 0);
    for (int t = 0; t < T; t++)
      Arrays.fill(state.operatingTheaterDayLoad[t], 0);

    // Populate Surgeon & OT Loads from Patients
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      int surg = input.PatientSurgeon(p.pIndex);
      state.surgeonDayLoad[surg][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
      state.operatingTheaterDayLoad[p.tIndex][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
    }

    // --- S1: Room Mixed Age & H1: Gender Mix ---
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        List<Integer> pIndices = state.roomDayOccupancy[r][d];
        if (pIndices.size() <= 1)
          continue;
        Map<IHTP_Input.Gender, Integer> genderCount = new HashMap<>();
        Map<Integer, Integer> ageCount = new HashMap<>();
        for (int pid : pIndices) {
          IHTP_Input.Gender gender;
          int age;
          if (pid < 0) {
            int occ = -(pid + 1);
            gender = input.OccupantGender(occ);
            age = input.OccupantAgeGroup(occ);
          } else {
            gender = input.PatientGender(pid);
            age = input.PatientAgeGroup(pid);
          }
          genderCount.put(gender, genderCount.getOrDefault(gender, 0) + 1);
          ageCount.put(age, ageCount.getOrDefault(age, 0) + 1);
        }
        // H1: Gender Mix
        if (genderCount.size() > 1)
          hard++;
        // S1: Age Mix
        if (ageCount.size() > 1)
          cost += w_age;
      }
    }

    // --- Calculate Nurse Loads & Check Coverage ---
    for (int r = 0; r < R; r++) {
      for (int s = 0; s < S; s++) {
        List<Integer> pIndices = state.roomDayOccupancy[r][s / 3];
        if (pIndices.isEmpty())
          continue;
        int nurse = state.roomShiftNurse[r][s];
        if (nurse == -1) {
          // H9: Uncovered Room
          hard++;
          continue;
        }
        // Calculate Load
        int load = 0;
        for (int pid : pIndices) {
          if (pid < 0) {
            int occ = -(pid + 1);
            load += input.OccupantWorkloadProduced(occ, s);
          } else {
            load += input.PatientWorkloadProduced(pid, s - state.patients.get(pid).assignedDay * input.ShiftsPerDay());
          }
        }
        state.nurseShiftLoad[nurse][s] = load;

        // S2: Nurse Skill
        int minSkill = 0;
        for (int pid : pIndices) {
          if (pid >= 0) {
            int relShift = s - state.patients.get(pid).assignedDay * input.ShiftsPerDay();
            minSkill = Math.max(minSkill, input.PatientSkillLevelRequired(pid, relShift));
          }
        }
        if (input.NurseSkillLevel(nurse) < minSkill)
          cost += w_skill;
      }
    }

    // H8 & S4: Nurse Presence & Excessive Workload
    for (OutputNurse n : state.nurses) {
      for (var entry : n.assignments.entrySet()) {
        int shiftIdx = entry.getKey();
        if (!entry.getValue().isEmpty()) {
          if (!input.IsNurseWorkingInShift(n.nIndex, shiftIdx))
            hard++; // H8: Nurse Presence
        }
      }
    }

    for (int n = 0; n < N; n++) {
      for (int s = 0; s < S; s++) {
        int load = state.nurseShiftLoad[n][s];
        int maxLoad = input.NurseMaxLoad(n, s);
        if (maxLoad > 0 && load > maxLoad)
          cost += w_load * (load - maxLoad);
      }
    }

    // S3: Continuity of Care
    for (int p = 0; p < P; p++) {
      OutputPatient op = state.patients.get(p);
      if (op.assignedDay == null)
        continue;
      int len = input.PatientLengthOfStay(p);
      List<Integer> nursesSet = new ArrayList<>();
      for (int d = op.assignedDay; d < Math.min(D, op.assignedDay + len); d++) {
        for (int sh = 0; sh < 3; sh++) {
          int shiftIdx = d * 3 + sh;
          if (op.rIndex >= 0 && op.rIndex < R) {
            int nurse = state.roomShiftNurse[op.rIndex][shiftIdx];
            if (nurse != -1 && !nursesSet.contains(nurse))
              nursesSet.add(nurse);
          }
        }
      }
      if (nursesSet.size() > 1)
        cost += w_cont * (nursesSet.size() - 1);
    }

    // Occupants Continuity
    for (int occ = 0; occ < input.Occupants(); occ++) {
      int r = input.OccupantRoom(occ);
      int len = input.OccupantLengthOfStay(occ);
      List<Integer> nursesSet = new ArrayList<>();
      for (int d = 0; d < Math.min(D, len); d++) {
        for (int sh = 0; sh < 3; sh++) {
          int shiftIdx = d * 3 + sh;
          if (r >= 0 && r < R) {
            int nurse = state.roomShiftNurse[r][shiftIdx];
            if (nurse != -1 && !nursesSet.contains(nurse))
              nursesSet.add(nurse);
          }
        }
      }
      if (nursesSet.size() > 1)
        cost += w_cont * (nursesSet.size() - 1);
    }

    // S5: Open Operating Theater
    int[] theaterUsedDays = new int[T * D];
    for (OutputPatient p : state.patients) {
      if (p.assignedDay != null && p.tIndex >= 0)
        theaterUsedDays[p.tIndex * D + p.assignedDay] = 1;
    }
    for (int i = 0; i < theaterUsedDays.length; i++) {
      if (theaterUsedDays[i] == 1)
        cost += w_ot;
    }

    // S6: Surgeon Transfer
    for (int surg = 0; surg < input.Surgeons(); surg++) {
      for (int d = 0; d < D; d++) {
        List<Integer> theaters = new ArrayList<>();
        for (OutputPatient p : state.patients) {
          if (p.assignedDay != null && p.assignedDay == d && input.PatientSurgeon(p.pIndex) == surg) {
            if (!theaters.contains(p.tIndex))
              theaters.add(p.tIndex);
          }
        }
        if (theaters.size() > 1)
          cost += w_trans * (theaters.size() - 1);
      }
    }

    // S7: Admission Delay
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      int release = input.PatientSurgeryReleaseDay(p.pIndex);
      if (p.assignedDay > release)
        cost += w_delay * (p.assignedDay - release);
    }

    // S8: Unscheduled Optional
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null) {
        if (!input.PatientMandatory(p.pIndex))
          cost += w_opt;
      }
    }

    // H2: Patient-Room Compatibility
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        for (int pid : state.roomDayOccupancy[r][d]) {
          if (pid >= 0 && input.IncompatibleRoom(pid, r))
            hard++;
        }
      }
    }

    // H3: Surgeon Overtime
    for (int s = 0; s < input.Surgeons(); s++) {
      for (int d = 0; d < D; d++) {
        if (state.surgeonDayLoad[s][d] > input.SurgeonMaxSurgeryTime(s, d))
          hard++;
      }
    }

    // H4: OT Overtime
    for (int t = 0; t < T; t++) {
      for (int d = 0; d < D; d++) {
        if (state.operatingTheaterDayLoad[t][d] > input.OperatingTheaterAvailability(t, d))
          hard++;
      }
    }

    // H6: Admission Day
    for (OutputPatient p : state.patients) {
      if (p.assignedDay != null) {
        int release = input.PatientSurgeryReleaseDay(p.pIndex);
        if (p.assignedDay < release)
          hard++;
      }
    }

    state.softCost = cost;
    state.hardViolations = hard;
  }

  // --- Moves ---

  abstract class Move {
    abstract void apply(SolutionState state);

    abstract void undo(SolutionState state);

    boolean isFeasible(SolutionState state) {
      return true;
    }
  }

  // 1. Atomic: Set Patient
  class MoveSetPatient extends Move {
    OutputPatient p;
    int oldDay, newDay;
    String oldRoom, newRoom;
    String oldT, newT;
    int pid, newRIdx, newTIdx;
    int oldRIdx, oldTIdx;

    public MoveSetPatient(OutputPatient p, int d, String r, String t) {
      this.p = p;
      this.pid = p.pIndex;
      this.oldDay = (p.assignedDay == null) ? -1 : p.assignedDay;
      this.newDay = d;
      this.oldRoom = p.assignedRoom;
      this.newRoom = r;
      this.oldT = p.assignedTheater;
      this.newT = t;
      this.oldRIdx = p.rIndex;
      this.oldTIdx = p.tIndex;
      this.newRIdx = (r != null) ? input.findRoomIndex(r) : -1;
      this.newTIdx = (t != null) ? input.findOperatingTheaterIndex(t) : -1;
    }

    void apply(SolutionState state) {
      // Remove old occupancy
      if (oldDay >= 0 && oldRIdx >= 0) {
        int len = input.PatientLengthOfStay(pid);
        for (int d = oldDay; d < Math.min(D, oldDay + len); d++)
          state.roomDayOccupancy[oldRIdx][d].remove(Integer.valueOf(pid));
        state.surgeonDayLoad[input.PatientSurgeon(pid)][oldDay] -= input.PatientSurgeryDuration(pid);
        if (oldTIdx >= 0)
          state.operatingTheaterDayLoad[oldTIdx][oldDay] -= input.PatientSurgeryDuration(pid);
      }
      // Add new
      if (newDay >= 0 && newRIdx >= 0) {
        int len = input.PatientLengthOfStay(pid);
        for (int d = newDay; d < Math.min(D, newDay + len); d++)
          state.roomDayOccupancy[newRIdx][d].add(pid);
        state.surgeonDayLoad[input.PatientSurgeon(pid)][newDay] += input.PatientSurgeryDuration(pid);
        if (newTIdx >= 0)
          state.operatingTheaterDayLoad[newTIdx][newDay] += input.PatientSurgeryDuration(pid);
      }
      p.assignedDay = (newDay >= 0) ? newDay : null;
      p.assignedRoom = newRoom;
      p.assignedTheater = newT;
      p.rIndex = newRIdx;
      p.tIndex = newTIdx;
    }

    void undo(SolutionState state) {
      // Remove new
      if (newDay >= 0 && newRIdx >= 0) {
        int len = input.PatientLengthOfStay(pid);
        for (int d = newDay; d < Math.min(D, newDay + len); d++)
          state.roomDayOccupancy[newRIdx][d].remove(Integer.valueOf(pid));
        state.surgeonDayLoad[input.PatientSurgeon(pid)][newDay] -= input.PatientSurgeryDuration(pid);
        if (newTIdx >= 0)
          state.operatingTheaterDayLoad[newTIdx][newDay] -= input.PatientSurgeryDuration(pid);
      }
      // Restore old
      if (oldDay >= 0 && oldRIdx >= 0) {
        int len = input.PatientLengthOfStay(pid);
        for (int d = oldDay; d < Math.min(D, oldDay + len); d++)
          state.roomDayOccupancy[oldRIdx][d].add(pid);
        state.surgeonDayLoad[input.PatientSurgeon(pid)][oldDay] += input.PatientSurgeryDuration(pid);
        if (oldTIdx >= 0)
          state.operatingTheaterDayLoad[oldTIdx][oldDay] += input.PatientSurgeryDuration(pid);
      }
      p.assignedDay = (oldDay >= 0) ? oldDay : null;
      p.assignedRoom = oldRoom;
      p.assignedTheater = oldT;
      p.rIndex = oldRIdx;
      p.tIndex = oldTIdx;
    }
  }

  // 2. Atomic: Remove Patient
  class MoveRemovePatient extends Move {
    OutputPatient p;
    int oldDay;
    String oldRoom, oldT;
    int oldRIdx, oldTIdx;

    public MoveRemovePatient(OutputPatient p) {
      this.p = p;
      this.oldDay = (p.assignedDay == null) ? -1 : p.assignedDay;
      this.oldRoom = p.assignedRoom;
      this.oldT = p.assignedTheater;
      this.oldRIdx = p.rIndex;
      this.oldTIdx = p.tIndex;
    }

    void apply(SolutionState state) {
      if (oldDay >= 0 && oldRIdx >= 0) {
        int len = input.PatientLengthOfStay(p.pIndex);
        for (int d = oldDay; d < Math.min(D, oldDay + len); d++)
          state.roomDayOccupancy[oldRIdx][d].remove(Integer.valueOf(p.pIndex));
        state.surgeonDayLoad[input.PatientSurgeon(p.pIndex)][oldDay] -= input.PatientSurgeryDuration(p.pIndex);
        if (oldTIdx >= 0)
          state.operatingTheaterDayLoad[oldTIdx][oldDay] -= input.PatientSurgeryDuration(p.pIndex);
      }
      p.assignedDay = null;
      p.assignedRoom = null;
      p.assignedTheater = null;
    }

    void undo(SolutionState state) {
      if (oldDay >= 0 && oldRIdx >= 0) {
        int len = input.PatientLengthOfStay(p.pIndex);
        for (int d = oldDay; d < Math.min(D, oldDay + len); d++)
          state.roomDayOccupancy[oldRIdx][d].add(p.pIndex);
        state.surgeonDayLoad[input.PatientSurgeon(p.pIndex)][oldDay] += input.PatientSurgeryDuration(p.pIndex);
        if (oldTIdx >= 0)
          state.operatingTheaterDayLoad[oldTIdx][oldDay] += input.PatientSurgeryDuration(p.pIndex);
      }
      p.assignedDay = oldDay;
      p.assignedRoom = oldRoom;
      p.assignedTheater = oldT;
    }
  }

  // 3. Chain: Swap Patients
  class MoveSwapPatients extends Move {
    OutputPatient p1, p2;
    Integer d1;
    String r1, t1;
    Integer d2;
    String r2, t2;

    public MoveSwapPatients(OutputPatient p1, OutputPatient p2) {
      this.p1 = p1;
      this.p2 = p2;
      this.d1 = p1.assignedDay;
      this.r1 = p1.assignedRoom;
      this.t1 = p1.assignedTheater;
      this.d2 = p2.assignedDay;
      this.r2 = p2.assignedRoom;
      this.t2 = p2.assignedTheater;
    }

    void apply(SolutionState state) {
      removeFromOccupancy(state, p1, d1, r1);
      removeFromOccupancy(state, p2, d2, r2);
      p1.assignedDay = d2;
      p1.assignedRoom = r2;
      p1.assignedTheater = t2;
      p1.rIndex = (r2 != null) ? input.findRoomIndex(r2) : -1;
      p1.tIndex = (t2 != null) ? input.findOperatingTheaterIndex(t2) : -1;
      p2.assignedDay = d1;
      p2.assignedRoom = r1;
      p2.assignedTheater = t1;
      p2.rIndex = (r1 != null) ? input.findRoomIndex(r1) : -1;
      p2.tIndex = (t1 != null) ? input.findOperatingTheaterIndex(t1) : -1;
      addToOccupancy(state, p1, d2, r2);
      addToOccupancy(state, p2, d1, r1);
    }

    void undo(SolutionState state) {
      removeFromOccupancy(state, p1, d2, r2);
      removeFromOccupancy(state, p2, d1, r1);
      p1.assignedDay = d1;
      p1.assignedRoom = r1;
      p1.assignedTheater = t1;
      p1.rIndex = (r1 != null) ? input.findRoomIndex(r1) : -1;
      p1.tIndex = (t1 != null) ? input.findOperatingTheaterIndex(t1) : -1;
      p2.assignedDay = d2;
      p2.assignedRoom = r2;
      p2.assignedTheater = t2;
      p2.rIndex = (r2 != null) ? input.findRoomIndex(r2) : -1;
      p2.tIndex = (t2 != null) ? input.findOperatingTheaterIndex(t2) : -1;
      addToOccupancy(state, p1, d1, r1);
      addToOccupancy(state, p2, d2, r2);
    }

    private void removeFromOccupancy(SolutionState s, OutputPatient p, Integer d, String r) {
      if (d == null || r == null)
        return;
      int rid = input.findRoomIndex(r);
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int day = d; day < Math.min(D, d + len); day++)
        s.roomDayOccupancy[rid][day].remove(Integer.valueOf(p.pIndex));
    }

    private void addToOccupancy(SolutionState s, OutputPatient p, Integer d, String r) {
      if (d == null || r == null)
        return;
      int rid = input.findRoomIndex(r);
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int day = d; day < Math.min(D, d + len); day++)
        s.roomDayOccupancy[rid][day].add(p.pIndex);
    }

    boolean isFeasible(SolutionState state) {
      if (p1.assignedDay == null || p2.assignedDay == null)
        return false;
      return true;
    }
  }

  // 4. Nurse Move: Swap Room
  class MoveSwapNurseRoomsSingle extends Move {
    OutputNurse n1, n2;
    int shift;
    String r1, r2;

    public MoveSwapNurseRoomsSingle(OutputNurse n1, OutputNurse n2, int s, String room1, String room2) {
      this.n1 = n1;
      this.n2 = n2;
      this.shift = s;
      this.r1 = room1;
      this.r2 = room2;
    }

    void apply(SolutionState s) {
      List<String> rooms1 = n1.assignments.getOrDefault(shift, new ArrayList<>());
      List<String> rooms2 = n2.assignments.getOrDefault(shift, new ArrayList<>());
      if (r1 != null)
        rooms1.remove(r1);
      if (r2 != null)
        rooms2.remove(r2);
      if (r2 != null)
        rooms1.add(r2);
      if (r1 != null)
        rooms2.add(r1);
      n1.assignments.put(shift, rooms1);
      n2.assignments.put(shift, rooms2);
    }

    void undo(SolutionState s) {
      List<String> rooms1 = n1.assignments.getOrDefault(shift, new ArrayList<>());
      List<String> rooms2 = n2.assignments.getOrDefault(shift, new ArrayList<>());
      if (r2 != null)
        rooms1.remove(r2);
      if (r1 != null)
        rooms2.remove(r1);
      if (r1 != null)
        rooms1.add(r1);
      if (r2 != null)
        rooms2.add(r2);
      n1.assignments.put(shift, rooms1);
      n2.assignments.put(shift, rooms2);
    }
  }

  private Move pickRandomMove() {
    if (P == 0)
      return null;
    int type = rand.nextInt(3); // 0=PtSet, 1=PtSwap, 2=Nurse

    if (type == 0) {
      OutputPatient p = currentState.patients.get(rand.nextInt(P));
      int d = rand.nextInt(D);
      String r = (R > 0) ? input.RoomId(rand.nextInt(R)) : null;
      String t = (T > 0) ? input.OperatingTheaterId(rand.nextInt(T)) : null;
      return new MoveSetPatient(p, d, r, t);
    } else if (type == 1) {
      if (P < 2)
        return pickRandomMove();
      int i1 = rand.nextInt(P);
      int i2 = rand.nextInt(P);
      while (i2 == i1)
        i2 = rand.nextInt(P);
      OutputPatient p1 = currentState.patients.get(i1);
      OutputPatient p2 = currentState.patients.get(i2);
      return new MoveSwapPatients(p1, p2);
    } else {
      if (N < 2 || R == 0 || S == 0)
        return pickRandomMove();
      int n1 = rand.nextInt(N);
      int n2 = rand.nextInt(N);
      while (n2 == n1)
        n2 = rand.nextInt(N);
      int shiftIdx = rand.nextInt(S);
      OutputNurse nurse1 = currentState.nurses.get(n1);
      OutputNurse nurse2 = currentState.nurses.get(n2);
      List<String> r1 = nurse1.assignments.getOrDefault(shiftIdx, new ArrayList<>());
      List<String> r2 = nurse2.assignments.getOrDefault(shiftIdx, new ArrayList<>());
      String room1 = r1.isEmpty() ? null : r1.get(rand.nextInt(r1.size()));
      String room2 = r2.isEmpty() ? null : r2.get(rand.nextInt(r2.size()));
      return new MoveSwapNurseRoomsSingle(nurse1, nurse2, shiftIdx, room1, room2);
    }
  }

  // --- Hill Climbing Loop Implementation ---
  public void run() {
    System.out.println("Starting Hill Climbing Optimization...");

    // Setup Log Writer
    java.io.BufferedWriter logWriter = null;
    if (logFilePath != null) {
      try {
        logWriter = new java.io.BufferedWriter(new java.io.FileWriter(logFilePath));
        logWriter.write("Iteration,Time(ms),Cost\n");
        logWriter.flush();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // Initial Eval
    evaluateFull(currentState);
    System.out.println("Initial Cost: Soft=" + currentState.softCost + ", Hard=" + currentState.hardViolations);
    if (currentState.hardViolations > 0) {
      System.out.println("WARNING: Initial solution has hard violations!");
    }

    bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);

    long startTime = System.currentTimeMillis();
    long endTime = startTime + (timeLimitSeconds * 1000);

    int iter = 0;
    int accepted = 0;
    int iterationsWithoutImprovement = 0;

    while (System.currentTimeMillis() < endTime && iterationsWithoutImprovement < maxIterationsWithoutImprovement) {
      Move move = pickRandomMove();
      if (move == null) {
        iter++;
        continue;
      }

      // Save current cost
      double oldCost = currentState.softCost;
      int oldHard = currentState.hardViolations;

      // Apply move
      move.apply(currentState);

      // Evaluate
      evaluateFull(currentState);

      double newCost = currentState.softCost;
      int newHard = currentState.hardViolations;

      // Hill Climbing Acceptance: Only accept if better or equal
      boolean accept = false;
      if (newHard < oldHard) {
        // Fewer hard violations - always accept
        accept = true;
      } else if (newHard == oldHard && newCost < oldCost) {
        // Same hard violations, but better soft cost - accept
        accept = true;
      } else if (newHard == oldHard && newCost == oldCost) {
        // Same quality - accept with small probability to allow exploration
        accept = (rand.nextDouble() < 0.1);
      }

      if (accept) {
        accepted++;
        iterationsWithoutImprovement = 0;

        // Update best if this is the best so far
        if (newHard < bestState.hardViolations
            || (newHard == bestState.hardViolations && newCost < bestState.softCost)) {
          bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);
          saveBest(bestState);
          System.out.println("New best at iter " + iter + ": Soft=" + newCost + ", Hard=" + newHard);
        }
      } else {
        // Reject: Undo move
        move.undo(currentState);
        currentState.softCost = oldCost;
        currentState.hardViolations = oldHard;
        iterationsWithoutImprovement++;
      }

      // Log & Flush every 1000 iter
      if (iter % 1000 == 0 && logWriter != null) {
        try {
          long elapsed = System.currentTimeMillis() - startTime;
          logWriter.write(iter + "," + elapsed + "," + currentState.softCost + "\n");
          logWriter.flush();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      iter++;

      // Progress report
      if (iter % 1000 == 0) {
        double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.println("Iter " + iter + " @ " + String.format("%.1f", elapsedSec) + "s | Current: Soft="
            + currentState.softCost + ", Hard=" + currentState.hardViolations + " | Best: Soft=" + bestState.softCost
            + ", Hard=" + bestState.hardViolations + " | Accepted: " + accepted + " | No Improve: "
            + iterationsWithoutImprovement);
      }
    }

    // Close log writer
    if (logWriter != null) {
      try {
        logWriter.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    System.out.println("Hill Climbing Finished. Best Soft Cost: " + bestState.softCost + ", Hard Violations: "
        + bestState.hardViolations);
  }

  private String getShiftName(int shiftIdx) {
    int perDay = input.ShiftsPerDay();
    int normalized = ((shiftIdx % perDay) + perDay) % perDay;
    switch (normalized) {
    case 0:
      return "early";
    case 1:
      return "late";
    case 2:
      return "night";
    default:
      return "shift_" + normalized;
    }
  }

  private void saveBest(SolutionState state) {
    try {
      JSONObject out = new JSONObject();

      // Patients
      JSONArray pArr = new JSONArray();
      for (OutputPatient p : state.patients) {
        JSONObject jp = new JSONObject();
        jp.put("id", p.id);
        if (p.assignedDay != null) {
          jp.put("admission_day", p.assignedDay);
          jp.put("room", p.assignedRoom);
          jp.put("operating_theater", p.assignedTheater);
        } else {
          jp.put("admission_day", "none");
          jp.put("room", JSONObject.NULL);
          jp.put("operating_theater", JSONObject.NULL);
        }
        pArr.put(jp);
      }
      out.put("patients", pArr);

      // Nurses
      JSONArray nArr = new JSONArray();
      for (OutputNurse n : state.nurses) {
        JSONObject jn = new JSONObject();
        jn.put("id", n.id);
        JSONArray aArr = new JSONArray();
        for (var entry : n.assignments.entrySet()) {
          int shiftIdx = entry.getKey();
          int day = shiftIdx / 3;
          String shift = getShiftName(shiftIdx % input.ShiftsPerDay());
          JSONObject ja = new JSONObject();
          ja.put("day", day);
          ja.put("shift", shift);
          JSONArray rArr = new JSONArray();
          for (String room : entry.getValue())
            rArr.put(room);
          ja.put("rooms", rArr);
          aArr.put(ja);
        }
        jn.put("assignments", aArr);
        nArr.put(jn);
      }
      out.put("nurses", nArr);

      // Costs
      JSONArray costs = new JSONArray();
      costs.put("Cost: " + state.softCost + ", Hard: " + state.hardViolations);
      out.put("costs", costs);

      FileWriter fw = new FileWriter(outputFilePath);
      fw.write(out.toString(2));
      fw.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // --- External Entry Point for Optimizer ---
  public static void runHC(String inFile, String solFile, int timeLimitMinutes, String logFile, String outFile) {
    try {
      IHTP_HillClimbing hc = new IHTP_HillClimbing(inFile, solFile, outFile);
      hc.logFilePath = logFile;
      hc.timeLimitSeconds = timeLimitMinutes * 60;
      hc.run();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // Fields for dynamicConfig
  public String logFilePath = null;

  public static void main(String[] args) {
    try {
      if (args.length < 5) {
        System.out.println(
            "Usage: java -cp .;json-20250107.jar IHTP_HillClimbing <instance> <solution> <time_limit_min> <log_csv> <output_json>");
        return;
      }

      String instanceFile = args[0];
      String solutionFile = args[1];
      int timeLimitMinutes = Integer.parseInt(args[2]);
      String logFile = args[3];
      String outputFile = args[4];

      IHTP_HillClimbing hc = new IHTP_HillClimbing(instanceFile, solutionFile, outputFile);
      hc.logFilePath = logFile;
      hc.timeLimitSeconds = timeLimitMinutes * 60;

      System.out.println("===========================================");
      System.out.println("   IHTP Hill Climbing Optimizer");
      System.out.println("===========================================");
      System.out.println("Instance      : " + instanceFile);
      System.out.println("Initial Sol   : " + solutionFile);
      System.out.println("Time Limit    : " + timeLimitMinutes + " minutes");
      System.out.println("Log File      : " + logFile);
      System.out.println("Output File   : " + outputFile);
      System.out.println("===========================================\n");

      hc.run();

      System.out.println("\n===========================================");
      System.out.println("   Optimization Complete!");
      System.out.println("===========================================");
      System.out.println("Best solution saved to: " + outputFile);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
