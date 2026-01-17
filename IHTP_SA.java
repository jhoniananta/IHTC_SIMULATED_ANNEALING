import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class IHTP_SA {

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
          s.roomDayOccupancy[r][d].addAll(this.roomDayOccupancy[r][d]);

      for (int n = 0; n < N; n++)
        System.arraycopy(this.nurseShiftLoad[n], 0, s.nurseShiftLoad[n], 0, S);

      for (int r = 0; r < R; r++)
        System.arraycopy(this.roomShiftNurse[r], 0, s.roomShiftNurse[r], 0, S);

      for (int surg = 0; surg < Surg; surg++)
        System.arraycopy(this.surgeonDayLoad[surg], 0, s.surgeonDayLoad[surg], 0, D);

      for (int t = 0; t < T; t++)
        System.arraycopy(this.operatingTheaterDayLoad[t], 0, s.operatingTheaterDayLoad[t], 0, D);

      return s;
    }
  }

  // --- Main Fields ---
  private IHTP_Input input;
  private SolutionState currentState;
  private SolutionState bestState;
  private Random rand = new Random(42); // Seed for reproducibility
  private int R, D, N, S, T, P;
  private String outputFilePath; // Added

  // Soft Constraint Weights
  private int w_age, w_skill, w_cont, w_load, w_ot, w_trans, w_delay, w_opt;

  public IHTP_SA(String instanceFile, String solutionFile, String outputFilePath) throws IOException {
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

  // SA Parameters
  private double initialTemperature = 5000.0; // Bisa dicoba temperature lain: 1000.0, 5000.0, 10000.0
  private double finalTemperature = 0.1;
  private int timeLimitSeconds = 600;

  private void loadSolution(String solutionFile) throws IOException {
    this.currentState = new SolutionState(R, D, N, S, input.Surgeons(), T);
    JSONObject j_sol = new JSONObject(new JSONTokener(new FileReader(solutionFile)));

    // 1. Initialize Patients from Input (ensure index i == pIndex)
    for (int i = 0; i < P; i++) {
      OutputPatient op = new OutputPatient();
      op.id = input.PatientId(i); // Assuming PatientId(i) exists
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
        if (jp.has("admission_day") && !jp.get("admission_day").toString().equals("none")) {
          op.assignedDay = jp.getInt("admission_day");
          op.assignedRoom = jp.getString("room");
          op.assignedTheater = jp.getString("operating_theater");
          op.rIndex = input.findRoomIndex(op.assignedRoom);
          op.tIndex = input.findOperatingTheaterIndex(op.assignedTheater);
        }
      }
    }
    // No need to sort, already indexed by pIndex

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
        int shiftIdx = input.findShiftIndex(shiftStr); // 0,1,2
        int globalShift = day * input.ShiftsPerDay() + shiftIdx;

        JSONArray rooms = ja.getJSONArray("rooms");
        List<String> rList = new ArrayList<>();
        for (int r = 0; r < rooms.length(); r++)
          rList.add(rooms.getString(r));

        on.assignments.put(globalShift, rList);
      }
      currentState.nurses.add(on);
    }
    currentState.nurses.sort((a, b) -> Integer.compare(a.nIndex, b.nIndex));
  }

  private void rebuildHelpers(SolutionState state) {
    // Clear helpers handled in constructor, just populate them
    // 1. Room Occupancy
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int d = p.assignedDay; d < Math.min(D, p.assignedDay + len); d++) {
        state.roomDayOccupancy[p.rIndex][d].add(p.pIndex);
      }
    }
    // Occupants (fixed)
    for (int occ = 0; occ < input.Occupants(); occ++) {
      int r = input.OccupantRoom(occ);
      int len = input.OccupantLengthOfStay(occ);
      for (int d = 0; d < Math.min(D, len); d++) {
        // We use negative index or separate list?
        // Let's use Patients + occ index for ID
        state.roomDayOccupancy[r][d].add(P + occ);
      }
    }

    // 2. Nurse Assignments
    for (OutputNurse n : state.nurses) {
      for (var entry : n.assignments.entrySet()) {
        int s = entry.getKey();
        for (String roomId : entry.getValue()) {
          int r = input.findRoomIndex(roomId);
          state.roomShiftNurse[r][s] = n.nIndex;
        }
      }
    }

    // 4. Surgeon & OT Loads - Populate from Patients
    // Reset first? New State has 0s.
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      // Surgeon
      int surg = input.PatientSurgeon(p.pIndex);
      state.surgeonDayLoad[surg][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
      // OT
      state.operatingTheaterDayLoad[p.tIndex][p.assignedDay] += input.PatientSurgeryDuration(p.pIndex);
    }
  }

  // --- Evaluation ---

  // Calculates Full Cost from scratch and updates state.softCost /
  // state.hardViolations
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

    // --- S1: Room Mixed Age (Hard & Soft Logic combined per room/day) ---
    // Also check H1: Room Gender Mix
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        List<Integer> occupants = state.roomDayOccupancy[r][d];
        if (occupants.isEmpty())
          continue;

        int minAge = Integer.MAX_VALUE, maxAge = Integer.MIN_VALUE;
        int countA = 0, countB = 0;

        for (int idx : occupants) {
          // Age
          int age = (idx < P) ? input.PatientAgeGroup(idx) : input.OccupantAgeGroup(idx - P);
          minAge = Math.min(minAge, age);
          maxAge = Math.max(maxAge, age);

          // Gender H1
          var gender = (idx < P) ? input.PatientGender(idx) : input.OccupantGender(idx - P);
          if (gender == IHTP_Input.Gender.A)
            countA++;
          else if (gender == IHTP_Input.Gender.B)
            countB++;
        }

        // S1
        if (maxAge > minAge)
          cost += (maxAge - minAge) * w_age;

        // H1
        if (countA > 0 && countB > 0)
          hard++;

        // H7: Room Capacity
        if (occupants.size() > input.RoomCapacity(r))
          hard++;
      }
    }

    // --- Calculate Nurse Loads & S2, S3, S4 ---
    // Need to iterate per room/shift to see which nurse handles it
    for (int r = 0; r < R; r++) {
      for (int s = 0; s < S; s++) {
        int day = s / input.ShiftsPerDay();
        List<Integer> occupants = state.roomDayOccupancy[r][day];

        int nIdx = state.roomShiftNurse[r][s];
        if (nIdx == -1) {
          // H9: Uncovered Room
          if (!occupants.isEmpty())
            hard++;
          continue;
        }

        for (int idx : occupants) {
          int workload = 0;
          int skillReq = 0;

          if (idx < P) {
            // Patient
            if (state.patients.get(idx).assignedDay == null)
              continue; // Safety check
            int admissionDay = state.patients.get(idx).assignedDay;
            int relShift = s - admissionDay * input.ShiftsPerDay();
            if (relShift >= 0 && relShift < input.PatientLengthOfStay(idx) * input.ShiftsPerDay()) {
              workload = input.PatientWorkloadProduced(idx, relShift);
              skillReq = input.PatientSkillLevelRequired(idx, relShift);
            }
          } else {
            // Occupant
            int occ = idx - P;
            workload = input.OccupantWorkloadProduced(occ, s);
            skillReq = input.OccupantSkillLevelRequired(occ, s);
          }

          state.nurseShiftLoad[nIdx][s] += workload;

          // S2: Nurse Skill
          int nSkill = input.NurseSkillLevel(nIdx);
          if (skillReq > nSkill) {
            cost += (skillReq - nSkill) * w_skill;
          }
        }
      }
    }

    // S4: Nurse Excessive Workload (after summing up)
    // S4 & H8: Nurse Excessive Workload & Presence
    for (OutputNurse n : state.nurses) {
      // H8 Check: Assigned shifts must be working shifts
      for (int s : n.assignments.keySet()) {
        if (!n.assignments.get(s).isEmpty()) {
          if (!input.IsNurseWorkingInShift(n.nIndex, s))
            hard++;
        }
      }
    }

    for (int n = 0; n < N; n++) {
      for (int s = 0; s < S; s++) {
        int maxLoad = input.NurseMaxLoad(n, s);
        if (state.nurseShiftLoad[n][s] > maxLoad) {
          cost += (state.nurseShiftLoad[n][s] - maxLoad) * w_load;
        }
      }
    }

    // S3: Continuity of Care (Distinct nurses per patient)
    for (int p = 0; p < P; p++) {
      OutputPatient op = state.patients.get(p);
      if (op.assignedDay == null)
        continue;

      // Count distinct nurses
      boolean[] seen = new boolean[N];
      int distinct = 0;

      int len = input.PatientLengthOfStay(p);
      int startS = op.assignedDay * input.ShiftsPerDay();
      int endS = (op.assignedDay + len) * input.ShiftsPerDay();

      for (int s = startS; s < endS; s++) {
        if (s >= S)
          break;
        int nIdx = state.roomShiftNurse[op.rIndex][s];
        if (nIdx != -1 && !seen[nIdx]) {
          seen[nIdx] = true;
          distinct++;
        }
      }
      if (distinct > 0)
        cost += distinct * w_cont;
    }
    // Occupants Continuity
    for (int occ = 0; occ < input.Occupants(); occ++) {
      int r = input.OccupantRoom(occ);
      int len = input.OccupantLengthOfStay(occ);
      boolean[] seen = new boolean[N];
      int distinct = 0;
      for (int s = 0; s < len * input.ShiftsPerDay(); s++) {
        // Ensure s < S
        if (s >= S)
          break;
        int nIdx = state.roomShiftNurse[r][s];
        if (nIdx != -1 && !seen[nIdx]) {
          seen[nIdx] = true;
          distinct++;
        }
      }
      if (distinct > 0)
        cost += distinct * w_cont;
    }

    // S5: Open Operating Theater
    int[] theaterUsedDays = new int[T * D]; // Flat map [t*D + d] -> count
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null)
        continue;
      theaterUsedDays[p.tIndex * D + p.assignedDay]++;
    }
    for (int i = 0; i < theaterUsedDays.length; i++) {
      if (theaterUsedDays[i] > 0)
        cost += w_ot;
    }

    // S6: Surgeon Transfer (Distinct theaters per day)
    for (int surg = 0; surg < input.Surgeons(); surg++) {
      for (int d = 0; d < D; d++) {
        boolean[] tUsed = new boolean[T];
        int tCount = 0;
        for (OutputPatient p : state.patients) {
          if (p.assignedDay != null && p.assignedDay == d && input.PatientSurgeon(p.pIndex) == surg) {
            if (!tUsed[p.tIndex]) {
              tUsed[p.tIndex] = true;
              tCount++;
            }
          }
        }
        if (tCount > 1)
          cost += (tCount - 1) * w_trans;
      }
    }

    // S7: Admission Delay
    for (OutputPatient p : state.patients) {
      if (p.assignedDay != null) {
        int release = input.PatientSurgeryReleaseDay(p.pIndex);
        if (p.assignedDay > release) {
          cost += (p.assignedDay - release) * w_delay;
        }
      }
    }

    // S8: Unscheduled Optional
    for (OutputPatient p : state.patients) {
      if (p.assignedDay == null) {
        if (!input.PatientMandatory(p.pIndex))
          cost += w_opt;
        else
          hard++; // H5 Mandatory unscheduled
      }
    }

    // H2: Patient-Room Compatibility (Incremental check during room loop or here)
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        for (int pid : state.roomDayOccupancy[r][d]) {
          if (pid < P) { // Patient
            if (input.IncompatibleRoom(pid, r))
              hard++;
          }
        }
      }
    }

    // H3: Surgeon Overtime
    for (int s = 0; s < input.Surgeons(); s++) {
      for (int d = 0; d < D; d++) {
        if (state.surgeonDayLoad[s][d] > input.SurgeonMaxSurgeryTime(s, d)) {
          hard++;
          // Could punish proportional to violations
        }
      }
    }

    // H4: OT Overtime
    for (int t = 0; t < T; t++) {
      for (int d = 0; d < D; d++) {
        if (state.operatingTheaterDayLoad[t][d] > input.OperatingTheaterAvailability(t, d)) {
          hard++;
        }
      }
    }

    // H6: Admission Day (inside existing S7 loop or separate)
    // S7 loop at 430 checks delay. Let's add H6 there or separately.
    for (OutputPatient p : state.patients) {
      if (p.assignedDay != null) {
        int release = input.PatientSurgeryReleaseDay(p.pIndex);
        int due = input.PatientLastPossibleDay(p.pIndex);
        if (p.assignedDay < release || p.assignedDay > due) {
          hard++;
        }
      }
    }

    state.softCost = cost;
    state.hardViolations = hard;
  }

  // --- Moves ---

  // --- Moves Definitions ---

  abstract class Move {
    abstract void apply(SolutionState state);

    abstract void undo(SolutionState state);

    // Basic feasibility check (e.g. bounds), specialized checks done in loop via
    // evaluateFull
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
    // Cache
    int pid, newRIdx, newTIdx;
    int oldRIdx, oldTIdx;

    public MoveSetPatient(OutputPatient p, int d, String r, String t) {
      this.p = p;
      this.newDay = d;
      this.newRoom = r;
      this.newT = t;
      this.oldDay = (p.assignedDay == null) ? -1 : p.assignedDay;
      this.oldRoom = p.assignedRoom;
      this.oldT = p.assignedTheater;
      this.pid = p.pIndex;
      this.newRIdx = input.findRoomIndex(r);
      this.newTIdx = input.findOperatingTheaterIndex(t);
      this.oldRIdx = (oldRoom != null) ? input.findRoomIndex(oldRoom) : -1;
      this.oldTIdx = (oldT != null) ? input.findOperatingTheaterIndex(oldT) : -1;
    }

    void apply(SolutionState state) {
      // Remove old
      if (oldDay != -1) {
        int len = input.PatientLengthOfStay(pid);
        for (int k = 0; k < len; k++) {
          int day = oldDay + k;
          if (day < D)
            state.roomDayOccupancy[oldRIdx][day].remove((Integer) pid);
        }
      }
      // Add new
      p.assignedDay = newDay;
      p.assignedRoom = newRoom;
      p.assignedTheater = newT;
      p.rIndex = newRIdx;
      p.tIndex = newTIdx;

      int len = input.PatientLengthOfStay(pid);
      for (int k = 0; k < len; k++) {
        int day = newDay + k;
        if (day < D)
          state.roomDayOccupancy[newRIdx][day].add(pid);
      }
    }

    void undo(SolutionState state) {
      // Remove new
      int len = input.PatientLengthOfStay(pid);
      for (int k = 0; k < len; k++) {
        int day = newDay + k;
        if (day < D)
          state.roomDayOccupancy[newRIdx][day].remove((Integer) pid);
      }
      // Restore old
      if (oldDay != -1) {
        p.assignedDay = oldDay;
        p.assignedRoom = oldRoom;
        p.assignedTheater = oldT;
        p.rIndex = oldRIdx;
        p.tIndex = oldTIdx;

        for (int k = 0; k < len; k++) {
          int day = oldDay + k;
          if (day < D)
            state.roomDayOccupancy[oldRIdx][day].add(pid);
        }
      } else {
        p.assignedDay = null;
        p.assignedRoom = null;
        p.assignedTheater = null;
        p.rIndex = -1;
        p.tIndex = -1;
      }
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
      this.oldDay = (p.assignedDay != null) ? p.assignedDay : -1;
      this.oldRoom = p.assignedRoom;
      this.oldT = p.assignedTheater;
      this.oldRIdx = (oldRoom != null) ? input.findRoomIndex(oldRoom) : -1;
      this.oldTIdx = (oldT != null) ? input.findOperatingTheaterIndex(oldT) : -1;
    }

    void apply(SolutionState state) {
      if (oldDay == -1)
        return; // already removed
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int k = 0; k < len; k++) {
        int d = oldDay + k;
        if (d < D)
          state.roomDayOccupancy[oldRIdx][d].remove((Integer) p.pIndex);
      }
      p.assignedDay = null;
      p.assignedRoom = null;
      p.assignedTheater = null;
      p.rIndex = -1;
      p.tIndex = -1;
    }

    void undo(SolutionState state) {
      if (oldDay == -1)
        return;
      p.assignedDay = oldDay;
      p.assignedRoom = oldRoom;
      p.assignedTheater = oldT;
      p.rIndex = oldRIdx;
      p.tIndex = oldTIdx;
      // Restore occupancy
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int k = 0; k < len; k++) {
        int d = oldDay + k;
        if (d < D)
          state.roomDayOccupancy[oldRIdx][d].add(p.pIndex);
      }
    }
  }

  // --- SA Loop Implementation ---
  public void run() {
    System.out.println("Starting SA Simulation...");

    // Setup Log Writer
    java.io.BufferedWriter logWriter = null;
    if (logFilePath != null) {
      try {
        logWriter = new java.io.BufferedWriter(new java.io.FileWriter(logFilePath));
        logWriter.write("Iteration,Time(ms),Cost,Temperature\n");
        logWriter.flush();
      } catch (IOException e) {
        System.err.println("Error creating log file: " + e.getMessage());
      }
    }

    // Initial Eval
    evaluateFull(currentState);
    System.out
        .println("Initial Cost calculated: Soft=" + currentState.softCost + ", Hard=" + currentState.hardViolations);
    if (currentState.hardViolations > 0) {
      System.err.println("WARNING: Initial solution has hard violations! SA might reject everything.");
    }

    bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);

    double T_curr = initialTemperature;
    long startTime = System.currentTimeMillis();
    long endTime = startTime + (timeLimitSeconds * 1000);
    double alpha = 0.9999; // Comment jika ingin menggunakan linear

    int iter = 0;
    int accepted = 0;

    while (System.currentTimeMillis() < endTime) {
      iter++;
      // 1. Pick Move
      Move move = pickRandomMove();
      if (move == null)
        continue;

      // 2. Apply and Check
      move.apply(currentState);

      // Snapshot scores before re-eval (if we had incremental, we wouldn't need full
      // re-eval)
      // But since we use evaluateFull for safety:
      // We need previous state's cost.
      // Since we applied move to currentState, we corrupted "previous".
      // We should trust evaluateFull to return new cost.
      // But we need old cost to calculate delta.
      // Better strategy: Store old cost before apply.
      // But evaluateFull modifies state.softCost in place.
      // So:
      // double oldSoft = currentState.softCost;
      // move.apply(currentState);
      // evaluateFull(currentState);
      // if(hard > 0) reject...

      // Re-logic:
      double zp_soft = currentState.softCost;
      int zp_hard = currentState.hardViolations;

      evaluateFull(currentState); // Updates cost in place

      boolean reject = false;

      // Feasibility check
      if (currentState.hardViolations > 0) {
        reject = true;
      } else {
        // Soft Delta
        double delta = currentState.softCost - zp_soft;
        if (delta < 0) {
          // Improvement
        } else {
          // Degradation
          if (rand.nextDouble() >= Math.exp(-delta / T_curr)) {
            reject = true;
          }
        }
      }

      if (reject) {
        // Undo
        move.undo(currentState);
        // Important: Restore scores!
        currentState.softCost = zp_soft;
        currentState.hardViolations = zp_hard;
      } else {
        accepted++;
        // Update Best
        if (currentState.softCost < bestState.softCost) {
          bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);
          System.out.println("New Best: " + bestState.softCost + " (Iter " + iter + ")");
          saveBest(bestState); // Periodic save
        }
      }

      // Cooling
      long now = System.currentTimeMillis();
      long elapsed = now - startTime;
      if (elapsed > timeLimitSeconds * 1000)
        break;

      // Yang dicomment untuk penurunan linear
      // double frac = (double) elapsed / (timeLimitSeconds * 1000);
      // T_curr = initialTemperature * (1.0 - frac);

      T_curr *= alpha; // Geometris cooling schedule nya

      if (T_curr < finalTemperature)
        T_curr = finalTemperature;

      // Log & Flush every 1000 iter
      if (iter % 1000 == 0 && logWriter != null) {
        try {
          logWriter.write(iter + "," + elapsed + "," + currentState.softCost + "," + T_curr + "\n");
          logWriter.flush();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    // Close log writer
    if (logWriter != null) {
      try {
        logWriter.close();
        System.out.println("Logs finished: " + logFilePath);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    System.out.println("SA Finished. Best Soft Cost: " + bestState.softCost);
  }

  private void saveBest(SolutionState state) {
    try {
      JSONObject root = new JSONObject();
      // 1. Patients
      JSONArray pats = new JSONArray();
      // Sort by ID to be deterministic
      List<OutputPatient> sortedP = new ArrayList<>(state.patients);
      sortedP.sort((a, b) -> a.id.compareTo(b.id)); // or pIndex

      for (OutputPatient op : sortedP) {
        JSONObject p = new JSONObject();
        p.put("id", op.id);
        if (op.assignedDay != null) {
          p.put("admission_day", op.assignedDay);
          p.put("room", op.assignedRoom);
          p.put("operating_theater", op.assignedTheater);
        } else {
          p.put("admission_day", "none");
        }
        pats.put(p);
      }
      root.put("patients", pats);

      // 2. Nurses
      JSONArray nurs = new JSONArray();
      List<OutputNurse> sortedN = new ArrayList<>(state.nurses);
      sortedN.sort((a, b) -> a.id.compareTo(b.id));

      for (OutputNurse on : sortedN) {
        JSONObject n = new JSONObject();
        n.put("id", on.id);
        JSONArray assigns = new JSONArray();

        // assignments map: shift -> list rooms
        // Need to sort assignments by day/shift
        List<Integer> shifts = new ArrayList<>(on.assignments.keySet());
        Collections.sort(shifts);

        for (int s : shifts) {
          List<String> rooms = on.assignments.get(s);
          if (rooms.isEmpty())
            continue;

          JSONObject a = new JSONObject();
          int d = s / input.ShiftsPerDay();
          int sh = s % input.ShiftsPerDay();
          a.put("day", d);
          a.put("shift", input.ShiftName(sh));

          JSONArray rArr = new JSONArray();
          for (String rm : rooms)
            rArr.put(rm);
          a.put("rooms", rArr);

          assigns.put(a);
        }
        n.put("assignments", assigns);
        nurs.put(n);
      }
      root.put("nurses", nurs);

      try (FileWriter fw = new FileWriter(this.outputFilePath)) {
        fw.write(root.toString(4));
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // 3. Chain: Swap Patients
  class MoveSwapPatients extends Move {
    OutputPatient p1, p2;
    // p1 old data
    Integer d1;
    String r1, t1;
    // p2 old data
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
      // Remove both from old spots
      removeFromOccupancy(state, p1, d1, r1);
      removeFromOccupancy(state, p2, d2, r2);

      // Swap assignments
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

      // Add to new spots
      addToOccupancy(state, p1, d2, r2);
      addToOccupancy(state, p2, d1, r1);
    }

    void undo(SolutionState state) {
      // Remove from swapped spots
      removeFromOccupancy(state, p1, d2, r2);
      removeFromOccupancy(state, p2, d1, r1);

      // Restore
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
      int rIdx = input.findRoomIndex(r);
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int k = 0; k < len; k++) {
        int dx = d + k;
        if (dx < D)
          s.roomDayOccupancy[rIdx][dx].remove((Integer) p.pIndex);
      }
    }

    private void addToOccupancy(SolutionState s, OutputPatient p, Integer d, String r) {
      if (d == null || r == null)
        return;
      int rIdx = input.findRoomIndex(r);
      int len = input.PatientLengthOfStay(p.pIndex);
      for (int k = 0; k < len; k++) {
        int dx = d + k;
        if (dx < D)
          s.roomDayOccupancy[rIdx][dx].add(p.pIndex);
      }
    }

    boolean isFeasible(SolutionState state) {
      // Basic hard constraint checks could be here
      // e.g. Incompatible Room?
      if (p1.assignedRoom != null && input.IncompatibleRoom(p1.pIndex, p1.rIndex))
        return false;
      if (p2.assignedRoom != null && input.IncompatibleRoom(p2.pIndex, p2.rIndex))
        return false;
      return true;
    }
  }

  // 4. Nurse Move: Swap Room
  class MoveSwapNurseRoomsSingle extends Move {
    OutputNurse n1, n2;
    int shift;
    String r1, r2; // Room IDs to swap. If one is null, it's a Give.

    public MoveSwapNurseRoomsSingle(OutputNurse n1, OutputNurse n2, int s, String room1, String room2) {
      this.n1 = n1;
      this.n2 = n2;
      this.shift = s;
      this.r1 = room1;
      this.r2 = room2;
    }

    void apply(SolutionState s) {
      // Apply to Maps in OutputNurse & state.roomShiftNurse
      List<String> list1 = n1.assignments.computeIfAbsent(shift, k -> new ArrayList<>());
      List<String> list2 = n2.assignments.computeIfAbsent(shift, k -> new ArrayList<>());

      if (r1 != null) {
        list1.remove(r1);
        list2.add(r1);
        s.roomShiftNurse[input.findRoomIndex(r1)][shift] = n2.nIndex;
      }
      if (r2 != null) {
        list2.remove(r2);
        list1.add(r2);
        s.roomShiftNurse[input.findRoomIndex(r2)][shift] = n1.nIndex;
      }
    }

    void undo(SolutionState s) {
      List<String> list1 = n1.assignments.get(shift);
      List<String> list2 = n2.assignments.get(shift);

      if (r2 != null) {
        list1.remove(r2);
        list2.add(r2);
        s.roomShiftNurse[input.findRoomIndex(r2)][shift] = n2.nIndex;
      }
      if (r1 != null) {
        list2.remove(r1);
        list1.add(r1);
        s.roomShiftNurse[input.findRoomIndex(r1)][shift] = n1.nIndex;
      }
    }
  }

  private Move pickRandomMove() {
    if (P == 0)
      return null;
    int type = rand.nextInt(3); // 0=PtSet, 1=PtSwap, 2=Nurse

    if (type == 0) {
      int r = rand.nextInt(P);
      OutputPatient p = currentState.patients.get(r);
      if (rand.nextDouble() < 0.1 && !input.PatientMandatory(p.pIndex)) {
        // Remove optional
        return new MoveRemovePatient(p);
      }
      int d = rand.nextInt(D);
      int rIdx = rand.nextInt(R);
      int tIdx = rand.nextInt(T);
      return new MoveSetPatient(p, d, input.RoomId(rIdx), input.OperatingTheaterId(tIdx));
    } else if (type == 1) {
      int i1 = rand.nextInt(P);
      int i2 = rand.nextInt(P);
      while (i1 == i2)
        i2 = rand.nextInt(P); // Ensure distinct
      return new MoveSwapPatients(currentState.patients.get(i1), currentState.patients.get(i2));
    } else {
      // Nurse Move
      int n1Idx = rand.nextInt(N);
      int n2Idx = rand.nextInt(N);
      if (n1Idx == n2Idx)
        return null;
      int s = rand.nextInt(S);

      OutputNurse n1 = currentState.nurses.get(n1Idx);
      OutputNurse n2 = currentState.nurses.get(n2Idx);

      // Check working
      if (!input.IsNurseWorkingInShift(n1.nIndex, s) || !input.IsNurseWorkingInShift(n2.nIndex, s))
        return null;

      List<String> l1 = n1.assignments.getOrDefault(s, new ArrayList<>());
      List<String> l2 = n2.assignments.getOrDefault(s, new ArrayList<>());

      String room1 = l1.isEmpty() ? null : l1.get(rand.nextInt(l1.size()));
      String room2 = l2.isEmpty() ? null : l2.get(rand.nextInt(l2.size()));

      if (room1 == null && room2 == null)
        return null;

      return new MoveSwapNurseRoomsSingle(n1, n2, s, room1, room2);
    }
  }

  // --- External Entry Point for Optimizer ---
  public static void runSA(String inFile, String solFile, int timeLimitMinutes, String logFile, String outFile) {
    try {
      IHTP_SA sa = new IHTP_SA(inFile, solFile, outFile);
      // Configure SA
      sa.timeLimitSeconds = timeLimitMinutes * 60;
      sa.logFilePath = logFile;
      sa.run();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // Fields for dynamicConfig
  public String logFilePath = null;

  public static void main(String[] args) {
    try {
      // Syntax A: standard (inFile, solFile, outFile)
      if (args.length == 3) {
        String inFile = args[0];
        String solFile = args[1];
        String outFile = args[2];
        IHTP_SA sa = new IHTP_SA(inFile, solFile, outFile);
        sa.run();
        return;
      }

      // Syntax B (Requested): IHTP_SA {pathTest.json} {max_hour} log.csv
      // solution.json
      if (args.length >= 4) {
        String inFile = args[0];
        double maxHour = Double.parseDouble(args[1]);
        String logFile = args[2];
        String outFile = args[3];

        // Infer input solution file?
        // User syntax didn't provide it.
        // We can assume we are refining "solutions/solution_<base>.json" or similar?
        // OR we can assume explicit 5th argument if provided.
        String solFile;
        if (args.length >= 5) {
          solFile = args[4];
        } else {
          // Try to infer from outFile path or generic default
          // e.g. "solutions/solution_i01.json"
          // For now, let's look for a file that matches the output name but maybe in a
          // different dir?
          // Or just fail safe:
          System.out.println("No input solution provided. Using default 'solution.json' for input...");
          solFile = "solution.json";
        }

        runSA(inFile, solFile, (int) (maxHour * 60), logFile, outFile);
        return;
      }

      // Default/Fallback
      System.out.println("Usage:");
      System.out.println("  1. java IHTP_SA <input.json> <input_sol.json> <output_sol.json>");
      System.out.println("  2. java IHTP_SA <input.json> <max_hours> <log.csv> <output_sol.json> [input_sol.json]");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
