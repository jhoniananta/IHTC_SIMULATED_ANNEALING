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

public class IHTP_HillClimb {

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

    long softCost = 0;
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
  private String outputFilePath;

  public IHTP_HillClimb(String instanceFile, String solutionFile, String outputFilePath) throws IOException {
    this.outputFilePath = outputFilePath;
    // 1. Load Input
    this.input = new IHTP_Input(instanceFile);
    this.P = input.Patients();
    this.R = input.Rooms();
    this.D = input.Days();
    this.N = input.Nurses();
    this.S = input.Shifts(); // Total shifts (Days * ShiftsPerDay)
    this.T = input.OperatingTheaters();

    // 2. Load Initial Solution
    loadSolution(solutionFile);

    // 3. Initialize Helper Structures
    rebuildHelpers(currentState);

    // 4. Initial Full Eval
    evaluateFull(currentState);

    // 5. Validate Initial Solution is Feasible
    if (currentState.hardViolations > 0) {
      System.err.println("ERROR: Initial solution has " + currentState.hardViolations + " hard violations!");
      System.err.println("Hill Climbing requires a feasible initial solution (0 violations).");
      throw new IllegalArgumentException("Initial solution is infeasible. Please provide a feasible solution.");
    }

    System.out.println("Initial solution validated: 0 hard violations, soft cost = " + currentState.softCost);

    this.bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);
  }

  // Hill Climbing Parameters
  private int timeLimitSeconds = 600;
  public String logFilePath = null;

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
      if (pIndex < 0 || pIndex >= P)
        continue;

      OutputPatient op = currentState.patients.get(pIndex);

      // Handle admission_day (can be int, string "none", or null)
      if (jp.has("admission_day") && !jp.isNull("admission_day")) {
        Object admissionObj = jp.get("admission_day");
        if (admissionObj instanceof Integer) {
          op.assignedDay = (Integer) admissionObj;
        } else if (admissionObj instanceof String) {
          String admStr = (String) admissionObj;
          if (!admStr.equals("none") && !admStr.isEmpty()) {
            try {
              op.assignedDay = Integer.parseInt(admStr);
            } catch (NumberFormatException e) {
              op.assignedDay = null;
            }
          }
        }
      }

      // Handle room (can be string room ID or "none")
      if (jp.has("room") && !jp.isNull("room")) {
        String roomStr = jp.getString("room");
        if (!roomStr.equals("none") && !roomStr.isEmpty()) {
          op.assignedRoom = roomStr;
          op.rIndex = input.findRoomIndex(op.assignedRoom);
        }
      }

      // Handle operating_theater (can be string theater ID or "none")
      if (jp.has("operating_theater") && !jp.isNull("operating_theater")) {
        String theaterStr = jp.getString("operating_theater");
        if (!theaterStr.equals("none") && !theaterStr.isEmpty()) {
          op.assignedTheater = theaterStr;
          op.tIndex = input.findOperatingTheaterIndex(op.assignedTheater);
        }
      }
    }

    // 3. Load Nurses
    JSONArray j_nurses = j_sol.getJSONArray("nurses");
    for (int i = 0; i < j_nurses.length(); i++) {
      JSONObject jn = j_nurses.getJSONObject(i);
      String id = jn.getString("id");
      int nIndex = input.findNurseIndex(id);
      if (nIndex < 0 || nIndex >= N)
        continue;

      OutputNurse on = new OutputNurse();
      on.id = id;
      on.nIndex = nIndex;

      // Handle both "shifts" and "assignments" keys (different formats)
      JSONArray j_shifts = null;
      if (jn.has("shifts")) {
        j_shifts = jn.getJSONArray("shifts");
      } else if (jn.has("assignments")) {
        j_shifts = jn.getJSONArray("assignments");
      }

      if (j_shifts != null) {
        for (int k = 0; k < j_shifts.length(); k++) {
          JSONObject jsh = j_shifts.getJSONObject(k);
          int day = jsh.getInt("day");

          // Handle both shift index (int) and shift name (string)
          int shiftIdx;
          if (jsh.has("shift")) {
            Object shiftObj = jsh.get("shift");
            if (shiftObj instanceof Integer) {
              shiftIdx = (Integer) shiftObj;
            } else {
              // It's a shift name string, convert to index
              String shiftName = (String) shiftObj;
              shiftIdx = input.findShiftIndex(shiftName);
            }
          } else {
            continue; // Skip if no shift info
          }

          int globalShift = day * input.ShiftsPerDay() + shiftIdx;

          List<String> rooms = new ArrayList<>();
          JSONArray j_rooms = jsh.getJSONArray("rooms");
          for (int r = 0; r < j_rooms.length(); r++) {
            rooms.add(j_rooms.getString(r));
          }
          on.assignments.put(globalShift, rooms);
        }
      }
      currentState.nurses.add(on);
    }
  }

  private void rebuildHelpers(SolutionState s) {
    // Clear all
    for (int r = 0; r < R; r++)
      for (int d = 0; d < D; d++)
        s.roomDayOccupancy[r][d].clear();

    for (int n = 0; n < N; n++)
      Arrays.fill(s.nurseShiftLoad[n], 0);

    for (int r = 0; r < R; r++)
      Arrays.fill(s.roomShiftNurse[r], -1);

    for (int surg = 0; surg < input.Surgeons(); surg++)
      Arrays.fill(s.surgeonDayLoad[surg], 0);

    for (int t = 0; t < T; t++)
      Arrays.fill(s.operatingTheaterDayLoad[t], 0);

    // Rebuild from patients - add to room for ALL days of length of stay
    for (OutputPatient op : s.patients) {
      if (op.assignedDay == null || op.rIndex < 0)
        continue;

      // Add patient to room for each day of their stay
      int los = input.PatientLengthOfStay(op.pIndex);
      for (int day = op.assignedDay; day < Math.min(D, op.assignedDay + los); day++) {
        s.roomDayOccupancy[op.rIndex][day].add(op.pIndex);
      }

      // Surgeon & Theater load (only on admission day)
      int surg = input.PatientSurgeon(op.pIndex);
      if (surg >= 0) {
        s.surgeonDayLoad[surg][op.assignedDay] += input.PatientSurgeryDuration(op.pIndex);
      }
      if (op.tIndex >= 0) {
        s.operatingTheaterDayLoad[op.tIndex][op.assignedDay] += input.PatientSurgeryDuration(op.pIndex);
      }
    }

    // Add Occupants (fixed patients) to room occupancy
    for (int occ = 0; occ < input.Occupants(); occ++) {
      int r = input.OccupantRoom(occ);
      int los = input.OccupantLengthOfStay(occ);
      for (int d = 0; d < Math.min(D, los); d++) {
        // Use negative index to distinguish occupants from patients
        s.roomDayOccupancy[r][d].add(-(occ + 1));
      }
    }

    // Rebuild from nurses
    for (OutputNurse on : s.nurses) {
      for (Map.Entry<Integer, List<String>> entry : on.assignments.entrySet()) {
        int shift = entry.getKey();
        if (shift >= S)
          continue; // Bounds check

        List<String> rooms = entry.getValue();

        // Calculate actual workload (not just room count!)
        int workload = 0;
        for (String roomId : rooms) {
          int rIndex = input.findRoomIndex(roomId);
          if (rIndex >= 0) {
            s.roomShiftNurse[rIndex][shift] = on.nIndex;

            // Calculate workload from all patients and occupants in this room
            int day = shift / input.ShiftsPerDay();
            for (int idx : s.roomDayOccupancy[rIndex][day]) {
              if (idx >= 0) {
                // Patient
                OutputPatient op = s.patients.get(idx);
                if (op.assignedDay != null) {
                  int relShift = shift - op.assignedDay * input.ShiftsPerDay();
                  if (relShift >= 0) {
                    workload += input.PatientWorkloadProduced(idx, relShift);
                  }
                }
              } else {
                // Occupant (negative index)
                int occIdx = -(idx + 1);
                workload += input.OccupantWorkloadProduced(occIdx, shift);
              }
            }
          }
        }
        s.nurseShiftLoad[on.nIndex][shift] = workload;
      }
    }
  }

  private void evaluateFull(SolutionState s) {
    s.hardViolations = 0;
    s.softCost = 0;

    // === HARD CONSTRAINTS ===

    // H1: Mandatory Unscheduled Patients
    for (OutputPatient p : s.patients) {
      if (input.PatientMandatory(p.pIndex) && p.assignedDay == null) {
        s.hardViolations++;
      }
    }

    // H2: Room Capacity
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        int capacity = input.RoomCapacity(r);
        int occupancy = s.roomDayOccupancy[r][d].size();
        if (occupancy > capacity) {
          s.hardViolations += (occupancy - capacity);
        }
      }
    }

    // H2: Room Gender Mix
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        List<Integer> pIndices = s.roomDayOccupancy[r][d];
        if (pIndices.size() <= 1)
          continue;

        boolean hasMale = false, hasFemale = false;
        for (int idx : pIndices) {
          IHTP_Input.Gender gender;
          if (idx >= 0) {
            // Patient
            gender = input.PatientGender(idx);
          } else {
            // Occupant
            gender = input.OccupantGender(-(idx + 1));
          }

          if (gender == IHTP_Input.Gender.A)
            hasMale = true;
          if (gender == IHTP_Input.Gender.B)
            hasFemale = true;
        }
        if (hasMale && hasFemale) {
          s.hardViolations++;
        }
      }
    }

    // H3: Patient Room Compatibility
    for (OutputPatient p : s.patients) {
      if (p.assignedRoom != null && input.IncompatibleRoom(p.pIndex, p.rIndex)) {
        s.hardViolations++;
      }
    }

    // H4: Surgeon Overtime
    for (int surg = 0; surg < input.Surgeons(); surg++) {
      for (int d = 0; d < D; d++) {
        int used = s.surgeonDayLoad[surg][d];
        int max = input.SurgeonMaxSurgeryTime(surg, d);
        if (used > max) {
          s.hardViolations++;
        }
      }
    }

    // H5: Operating Theater Overtime
    for (int t = 0; t < T; t++) {
      for (int d = 0; d < D; d++) {
        int used = s.operatingTheaterDayLoad[t][d];
        int max = input.OperatingTheaterAvailability(t, d);
        if (used > max) {
          s.hardViolations++;
        }
      }
    }

    // H6: Admission Day (release day <= admission <= last possible day)
    for (OutputPatient p : s.patients) {
      if (p.assignedDay != null) {
        if (p.assignedDay < input.PatientSurgeryReleaseDay(p.pIndex)
            || p.assignedDay > input.PatientLastPossibleDay(p.pIndex)) {
          s.hardViolations++;
        }
      }
    }

    // H7: Nurse Presence (nurse only assigned to working shifts)
    for (int r = 0; r < R; r++) {
      for (int shift = 0; shift < S; shift++) {
        int nIdx = s.roomShiftNurse[r][shift];
        if (nIdx != -1 && !input.IsNurseWorkingInShift(nIdx, shift)) {
          s.hardViolations++;
        }
      }
    }

    // H8: Uncovered Room (room must have nurse if has patients)
    for (int r = 0; r < R; r++) {
      for (int shift = 0; shift < S; shift++) {
        int day = shift / input.ShiftsPerDay();
        if (s.roomShiftNurse[r][shift] == -1 && s.roomDayOccupancy[r][day].size() > 0) {
          s.hardViolations++;
        }
      }
    }

    // === SOFT CONSTRAINTS ===

    // S1: Room Age Mix (w_age) - multiply weight PER ITEM like GD
    for (int r = 0; r < R; r++) {
      for (int d = 0; d < D; d++) {
        List<Integer> pIndices = s.roomDayOccupancy[r][d];
        if (pIndices.isEmpty())
          continue;

        int minAge = Integer.MAX_VALUE;
        int maxAge = Integer.MIN_VALUE;
        for (int idx : pIndices) {
          int ageGroup;
          if (idx >= 0) {
            // Patient
            ageGroup = input.PatientAgeGroup(idx);
          } else {
            // Occupant (negative index)
            ageGroup = input.OccupantAgeGroup(-(idx + 1));
          }

          if (ageGroup < minAge)
            minAge = ageGroup;
          if (ageGroup > maxAge)
            maxAge = ageGroup;
        }
        if (maxAge > minAge) {
          s.softCost += (long) (maxAge - minAge) * input.weights[0];
        }
      }
    }

    // S2: Room Skill Level (w_skill) - multiply weight PER ITEM like GD
    for (int r = 0; r < R; r++) {
      for (int shift = 0; shift < S; shift++) {
        int nIdx = s.roomShiftNurse[r][shift];
        if (nIdx < 0)
          continue;

        int nurseSkill = input.NurseSkillLevel(nIdx);
        int day = shift / input.ShiftsPerDay();

        for (int idx : s.roomDayOccupancy[r][day]) {
          int requiredSkill;
          if (idx >= 0) {
            // Patient - use relative shift from admission day (like validator)
            OutputPatient op = s.patients.get(idx);
            if (op.assignedDay == null)
              continue; // Skip unscheduled patients

            int rel = shift - op.assignedDay * input.ShiftsPerDay();
            if (rel >= 0) {
              requiredSkill = input.PatientSkillLevelRequired(idx, rel);
              if (nurseSkill < requiredSkill) {
                s.softCost += (long) (requiredSkill - nurseSkill) * input.weights[1];
              }
            }
          } else {
            // Occupant (negative index) - use absolute shift
            int occIdx = -(idx + 1);
            requiredSkill = input.OccupantSkillLevelRequired(occIdx, shift);
            if (nurseSkill < requiredSkill) {
              s.softCost += (long) (requiredSkill - nurseSkill) * input.weights[1];
            }
          }
        }
      }
    }

    // S3: Continuity of Care (w_cont) - multiply weight PER PATIENT/OCCUPANT like
    // GD
    // Calculate for all patients (scheduled)
    for (OutputPatient op : s.patients) {
      if (op.assignedDay == null || op.rIndex < 0)
        continue;

      boolean[] seenNurses = new boolean[N];
      int distinctNurses = 0;

      int los = input.PatientLengthOfStay(op.pIndex);
      int maxShifts = Math.min(los, D - op.assignedDay) * input.ShiftsPerDay();
      for (int relShift = 0; relShift < maxShifts; relShift++) {
        int shift = op.assignedDay * input.ShiftsPerDay() + relShift;
        if (shift >= S)
          break;
        int nIdx = s.roomShiftNurse[op.rIndex][shift];
        if (nIdx >= 0 && !seenNurses[nIdx]) {
          seenNurses[nIdx] = true;
          distinctNurses++;
        }
      }
      if (distinctNurses > 0) {
        s.softCost += (long) distinctNurses * input.weights[2];
      }
    }

    // Calculate for all occupants (always present)
    for (int occIdx = 0; occIdx < input.Occupants(); occIdx++) {
      boolean[] seenNurses = new boolean[N];
      int distinctNurses = 0;

      int rIdx = input.OccupantRoom(occIdx);
      if (rIdx < 0 || rIdx >= R)
        continue;

      // Check shifts up to occupant's length of stay (like validator)
      int maxShifts = Math.min(input.OccupantLengthOfStay(occIdx) * input.ShiftsPerDay(), S);
      for (int shift = 0; shift < maxShifts; shift++) {
        int nIdx = s.roomShiftNurse[rIdx][shift];
        if (nIdx >= 0 && !seenNurses[nIdx]) {
          seenNurses[nIdx] = true;
          distinctNurses++;
        }
      }
      if (distinctNurses > 0) {
        s.softCost += (long) distinctNurses * input.weights[2];
      }
    }

    // S4: Nurse Excessive Workload (w_load) - multiply weight PER OVERLOAD like GD
    // Only check nurse's working shifts
    for (int n = 0; n < N; n++) {
      for (int i = 0; i < input.NurseWorkingShifts(n); i++) {
        int shift = input.NurseWorkingShift(n, i);
        int load = s.nurseShiftLoad[n][shift];
        int maxLoad = input.NurseMaxLoad(n, shift);
        if (load > maxLoad) {
          s.softCost += (long) (load - maxLoad) * input.weights[3];
        }
      }
    }

    // S5: Open Operating Theater (w_ot) - multiply weight PER THEATER-DAY like GD
    for (int t = 0; t < T; t++) {
      for (int d = 0; d < D; d++) {
        if (s.operatingTheaterDayLoad[t][d] > 0) {
          s.softCost += input.weights[4];
        }
      }
    }

    // S6: Surgeon Transfer (w_trans) - multiply weight PER TRANSFER like GD
    for (int surg = 0; surg < input.Surgeons(); surg++) {
      for (int d = 0; d < D; d++) {
        int count = 0;
        // Count how many different theaters this surgeon uses on day d
        for (int t = 0; t < T; t++) {
          boolean used = false;
          for (OutputPatient op : s.patients) {
            if (op.assignedDay != null && op.assignedDay == d && input.PatientSurgeon(op.pIndex) == surg
                && op.tIndex == t) {
              used = true;
              break;
            }
          }
          if (used)
            count++;
        }
        if (count > 1) {
          s.softCost += (long) (count - 1) * input.weights[5];
        }
      }
    }

    // S7: Patient Delay (w_delay) - multiply weight PER DAY DELAYED like GD
    for (OutputPatient op : s.patients) {
      if (op.assignedDay != null) {
        int release = input.PatientSurgeryReleaseDay(op.pIndex);
        if (op.assignedDay > release) {
          s.softCost += (long) (op.assignedDay - release) * input.weights[6];
        }
      }
    }

    // S8: Unscheduled Optional (w_opt) - multiply weight PER UNSCHEDULED like GD
    for (OutputPatient op : s.patients) {
      if (op.assignedDay == null && !input.PatientMandatory(op.pIndex)) {
        s.softCost += input.weights[7];
      }
    }
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
        // Remove old surgeon and theater loads
        int surg = input.PatientSurgeon(pid);
        if (surg >= 0) {
          state.surgeonDayLoad[surg][oldDay] -= input.PatientSurgeryDuration(pid);
        }
        if (oldTIdx >= 0) {
          state.operatingTheaterDayLoad[oldTIdx][oldDay] -= input.PatientSurgeryDuration(pid);
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
      // Add new surgeon and theater loads
      int surg = input.PatientSurgeon(pid);
      if (surg >= 0) {
        state.surgeonDayLoad[surg][newDay] += input.PatientSurgeryDuration(pid);
      }
      if (newTIdx >= 0) {
        state.operatingTheaterDayLoad[newTIdx][newDay] += input.PatientSurgeryDuration(pid);
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
      // Remove new surgeon and theater loads
      int surg = input.PatientSurgeon(pid);
      if (surg >= 0) {
        state.surgeonDayLoad[surg][newDay] -= input.PatientSurgeryDuration(pid);
      }
      if (newTIdx >= 0) {
        state.operatingTheaterDayLoad[newTIdx][newDay] -= input.PatientSurgeryDuration(pid);
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
        // Restore old surgeon and theater loads
        if (surg >= 0) {
          state.surgeonDayLoad[surg][oldDay] += input.PatientSurgeryDuration(pid);
        }
        if (oldTIdx >= 0) {
          state.operatingTheaterDayLoad[oldTIdx][oldDay] += input.PatientSurgeryDuration(pid);
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
      // Remove surgeon and theater loads
      int surg = input.PatientSurgeon(p.pIndex);
      if (surg >= 0) {
        state.surgeonDayLoad[surg][oldDay] -= input.PatientSurgeryDuration(p.pIndex);
      }
      if (oldTIdx >= 0) {
        state.operatingTheaterDayLoad[oldTIdx][oldDay] -= input.PatientSurgeryDuration(p.pIndex);
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
      // Restore surgeon and theater loads
      int surg = input.PatientSurgeon(p.pIndex);
      if (surg >= 0) {
        state.surgeonDayLoad[surg][oldDay] += input.PatientSurgeryDuration(p.pIndex);
      }
      if (oldTIdx >= 0) {
        state.operatingTheaterDayLoad[oldTIdx][oldDay] += input.PatientSurgeryDuration(p.pIndex);
      }
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

  // Pure Hill Climbing main loop - deterministic, stops at local optimum
  public void run() throws IOException {
    long startTime = System.currentTimeMillis();
    long endTime = startTime + (timeLimitSeconds * 1000L);

    int iteration = 0;
    int improvementCount = 0;

    System.out.println("\n=== Hill Climbing Optimization Started (MUCH Worse Strategy) ===");
    System.out.println("Strategy: Accept Equal/Worse + 10-move Sampling + SetPatient Only + Random Bad Choices");
    System.out.println("Initial Cost: " + currentState.softCost);
    System.out.println("Initial Hard Violations: " + currentState.hardViolations);

    // Setup CSV logging
    String csvHeader = "iteration,time_sec,current_cost,best_cost,hard_violations,improvements";
    int logInterval = 100; // Log every 100 iterations

    if (logFilePath != null) {
      java.io.File logFile = new java.io.File(logFilePath);
      if (logFile.getParentFile() != null && !logFile.getParentFile().exists()) {
        logFile.getParentFile().mkdirs();
      }
      writeHeader(logFilePath, csvHeader);
    }

    while (System.currentTimeMillis() < endTime) {
      iteration++;

      // Try to explore neighbor (may accept equal/worse in MUCH Worse Strategy)
      boolean moveAccepted = exploreNeighbors();

      // IMPORTANT: Only count as TRUE improvement if better than bestState
      // This follows pure Hill Climbing principle: only track strictly better
      // solutions
      if (moveAccepted && currentState.hardViolations == 0 && currentState.softCost < bestState.softCost) {
        // TRUE improvement found - update bestState
        improvementCount++;
        long oldBest = bestState.softCost;
        bestState = currentState.copy(R, D, N, S, input.Surgeons(), T);
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println(String.format("Iteration %d [%ds]: NEW BEST %d -> %d (delta: -%d)", iteration, elapsed,
            oldBest, bestState.softCost, oldBest - bestState.softCost));
      }
      // Note: Continue exploration even if current move made things worse

      // Logging to CSV every logInterval iterations
      if (iteration % logInterval == 0) {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        if (logFilePath != null) {
          double elapsedSec = elapsed;
          String logEntry = String.format(java.util.Locale.US, "%d,%.2f,%d,%d,%d,%d", iteration, elapsedSec,
              currentState.softCost, bestState.softCost, currentState.hardViolations, improvementCount);
          appendLogEntry(logFilePath, logEntry);
        }
      }
    }

    System.out.println("\n=== Hill Climbing Complete ===");
    System.out.println("Total Iterations: " + iteration);
    System.out.println("Total Improvements: " + improvementCount);
    System.out.println("Best Cost: " + bestState.softCost);
    System.out.println("Best Hard Violations: " + bestState.hardViolations);

    // Write final log entry
    if (logFilePath != null) {
      double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
      String logEntry = String.format(java.util.Locale.US, "%d,%.2f,%d,%d,%d,%d", iteration, elapsedSec,
          currentState.softCost, bestState.softCost, bestState.hardViolations, improvementCount);
      appendLogEntry(logFilePath, logEntry);
    }

    // Write best solution
    writeSolution(bestState);

    // Validate final solution to ensure cost consistency
    System.out.println("\n=== Final Validation ===");
    rebuildHelpers(bestState);
    evaluateFull(bestState);
    System.out.println("Final recalculated cost: " + bestState.softCost);
    System.out.println("Final recalculated violations: " + bestState.hardViolations);
  }

  private boolean exploreNeighbors() {
    // MUCH WORSE STRATEGY: Random single neighbor with lenient acceptance
    Move move = pickRandomMove();
    if (move == null)
      return false;

    // Store old costs
    long oldSoft = currentState.softCost;
    int oldHard = currentState.hardViolations;

    // Apply move
    move.apply(currentState);
    rebuildHelpers(currentState);
    evaluateFull(currentState);

    // CRITICAL: Only accept FEASIBLE solutions (0 hard violations)
    if (currentState.hardViolations > 0) {
      // Reject infeasible solution
      move.undo(currentState);
      rebuildHelpers(currentState);
      evaluateFull(currentState);
      return false;
    }

    // WORSE ACCEPTANCE: Accept equal cost (plateau search) OR slight worsening
    long costDiff = currentState.softCost - oldSoft;

    // Accept if: cost improves OR cost equal OR small worsening with 30%
    // probability
    if (costDiff <= 0) {
      // Improving or equal - always accept
      return true;
    } else if (costDiff <= 500 && rand.nextDouble() < 0.3) {
      // Small worsening - accept with 30% probability (BAD STRATEGY)
      return true;
    } else {
      // Reject
      move.undo(currentState);
      rebuildHelpers(currentState);
      evaluateFull(currentState);
      return false;
    }
  }

  private Move pickRandomMove() {
    // MUCH WORSE STRATEGY: Extremely limited neighborhood sampling
    // Generate all moves but only sample a TINY subset
    List<Move> allMoves = generateAllMoves(currentState);

    if (allMoves.isEmpty())
      return null;

    // SEVERE LIMITATION: Only consider first 10 moves (was 50)
    int sampleSize = Math.min(10, allMoves.size());

    // Shuffle to randomize which 10 we pick
    Collections.shuffle(allMoves, rand);
    List<Move> sampledMoves = allMoves.subList(0, sampleSize);

    // WORSE: Sometimes skip good moves randomly (20% chance to pick poorly)
    if (rand.nextDouble() < 0.2 && sampledMoves.size() > 1) {
      // Pick from last half (worse moves if sorted)
      int badIndex = sampledMoves.size() / 2 + rand.nextInt(sampledMoves.size() / 2);
      return sampledMoves.get(badIndex);
    }
    // Pick random move from sampled subset
    return sampledMoves.get(rand.nextInt(sampledMoves.size()));
  }

  /**
   * Generate moves with constraints to increase feasibility rate Optimized to
   * reduce infeasible moves and improve hill climbing performance
   */
  private List<Move> generateAllMoves(SolutionState state) {
    List<Move> moves = new ArrayList<>();

    // MUCH WORSE STRATEGY: Only SetPatient moves (no Remove, Swap, or Nurse moves)
    // Also skip many days and rooms to severely reduce neighborhood size
    for (int p = 0; p < P; p++) {
      OutputPatient patient = state.patients.get(p);
      int pIdx = patient.pIndex;

      // Get valid day range for this patient
      int releaseDay = input.PatientSurgeryReleaseDay(pIdx);
      int lastDay = input.PatientLastPossibleDay(pIdx);

      // WORSE: Skip first/last 25% of valid days
      int dayRange = lastDay - releaseDay;
      int skipDays = dayRange / 4;
      int startDay = releaseDay + skipDays;
      int endDay = lastDay - skipDays;

      // Only try reduced day range
      for (int d = startDay; d <= endDay && d < D; d++) {
        // WORSE: Only try every other room (skip 50% of rooms)
        for (int r = 0; r < R; r += 2) {
          if (input.IncompatibleRoom(pIdx, r))
            continue; // Skip incompatible rooms

          // Only try theaters with enough availability
          int surgeryDuration = input.PatientSurgeryDuration(pIdx);
          for (int t = 0; t < T; t++) {
            // Quick feasibility check: theater has capacity on this day
            if (input.OperatingTheaterAvailability(t, d) >= surgeryDuration) {
              moves.add(new MoveSetPatient(patient, d, input.RoomId(r), input.OperatingTheaterId(t)));
              // WORSE: Only use first valid theater, don't try all theaters
              break;
            }
          }
        }
      }
    }

    // REMOVED: No RemovePatient, SwapPatients, or Nurse moves
    // This severely limits the search space and move diversity

    return moves;
  }

  /**
   * Write CSV header to file (overwrite mode)
   */
  private void writeHeader(String path, String header) {
    try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(path, false))) {
      pw.println(header);
    } catch (Exception e) {
      System.err.println("Error writing CSV header: " + e.getMessage());
    }
  }

  /**
   * Append single log entry to CSV file
   */
  private void appendLogEntry(String path, String entry) {
    try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(path, true))) {
      pw.println(entry);
    } catch (Exception e) {
      System.err.println("Error appending to CSV: " + e.getMessage());
    }
  }

  private void writeSolution(SolutionState s) throws IOException {
    JSONObject root = new JSONObject();

    // Patients
    JSONArray pArr = new JSONArray();
    for (OutputPatient p : s.patients) {
      JSONObject obj = new JSONObject();
      obj.put("id", p.id);
      if (p.assignedDay == null) {
        obj.put("admission_day", "none");
      } else {
        obj.put("admission_day", p.assignedDay);
        obj.put("room", p.assignedRoom);
        obj.put("operating_theater", p.assignedTheater);
      }
      pArr.put(obj);
    }
    root.put("patients", pArr);

    // Nurses
    JSONArray nArr = new JSONArray();
    for (OutputNurse n : s.nurses) {
      JSONObject obj = new JSONObject();
      obj.put("id", n.id);
      JSONArray assigns = new JSONArray();

      // Sort assignments by shift for deterministic output
      List<Integer> shifts = new ArrayList<>(n.assignments.keySet());
      Collections.sort(shifts);

      for (int globalShift : shifts) {
        List<String> rooms = n.assignments.get(globalShift);
        if (rooms.isEmpty())
          continue;

        int day = globalShift / input.ShiftsPerDay();
        String shiftStr = input.ShiftName(globalShift % input.ShiftsPerDay());

        JSONObject aObj = new JSONObject();
        aObj.put("day", day);
        aObj.put("shift", shiftStr);
        aObj.put("rooms", new JSONArray(rooms));
        assigns.put(aObj);
      }
      obj.put("assignments", assigns);
      nArr.put(obj);
    }
    root.put("nurses", nArr);

    // Write to file
    try (FileWriter fw = new FileWriter(outputFilePath)) {
      fw.write(root.toString(2));
    }
  }

  // --- Static method for IHTP_OptimizerHill ---
  public static void runHillClimbingFromLauncher(String inFile, int timeLimitMinutes, String logFile, String outFile,
      String initialSol) {
    try {
      IHTP_HillClimb hc = new IHTP_HillClimb(inFile, initialSol, outFile);
      hc.timeLimitSeconds = timeLimitMinutes * 60;
      hc.logFilePath = logFile;
      hc.run();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    if (args.length < 4) {
      System.out
          .println("Usage: java IHTP_HillClimb <instance.json> <minutes> <log.csv> <out.json> [initial_sol.json]");
      return;
    }

    try {
      String inFile = args[0];
      int timeMin = Integer.parseInt(args[1]);
      String logFile = args[2];
      String outFile = args[3];
      String solFile = (args.length > 4) ? args[4] : null;

      if (solFile == null) {
        System.out.println("No initial solution provided. Generating feasible solution...");
        solFile = "temp_hc_init_" + System.currentTimeMillis() + ".json";
        boolean found = IHTP_Solution.solve(inFile, 1, null, solFile);
        if (!found) {
          System.err.println("Failed to generate initial feasible solution.");
          return;
        }
      }

      runHillClimbingFromLauncher(inFile, timeMin, logFile, outFile, solFile);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}