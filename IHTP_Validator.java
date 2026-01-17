import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class IHTP_Validator {
  // Compute violations
  private static int vRoomGender;
  private static int vRoomCompat;
  private static int vSurgeonOver;
  private static int vOTOver;
  private static int vMandatoryUnsch;
  private static int vAdmissionDay;
  private static int vRoomCap;
  private static int vNursePres;
  private static int vUncovered;
  private static int totalViolations;
  private static long totalCost;

  // Output (solution) validator class
  static class IHTP_Output {
    private final IHTP_Input in;
    private final boolean VERBOSE;
    // Patient assignment arrays
    int[] admissionDay;
    int[] assignedRoom;
    int[] assignedTheater;
    // Nurse assignment matrix (room x shift -> nurse index or -1)
    int[][] roomShiftNurse;
    // Nurse assignment lists (nurse x shift -> list of rooms covered)
    List<Integer>[][] nurseShiftRoomList;
    // Nurse workload per shift (nurse x shift)
    int[][] nurseShiftLoad;
    // Occupancy data (room x day -> list of occupant indices)
    List<Integer>[][] roomDayPatientList;
    int[][] roomDayAPatients;
    int[][] roomDayBPatients;
    // Operating theater usage (theater x day -> list of patient indices)
    List<Integer>[][] operatingTheaterDayPatientList;
    int[][] operatingTheaterDayLoad;
    // Surgeon usage (surgeon x day -> total time and theater count)
    int[][] surgeonDayLoad;
    int[][][] surgeonDayTheaterCount;
    // Nurse assignment to each patient/occupant's each shift of stay
    @SuppressWarnings("unchecked")
    List<Integer>[] patientShiftNurse;

    @SuppressWarnings("unchecked")
    public IHTP_Output(IHTP_Input input, String solutionFile, boolean verbose) throws IOException {
      this.in = input;
      this.VERBOSE = verbose;
      int P = in.Patients(), O = in.Occupants();
      int R = in.Rooms(), N = in.Nurses();
      int T = in.OperatingTheaters(), S = in.Shifts(), D = in.Days();

      // Initialize arrays
      admissionDay = new int[P];
      assignedRoom = new int[P];
      assignedTheater = new int[P];
      roomShiftNurse = new int[R][S];
      nurseShiftRoomList = (List<Integer>[][]) new List[N][S];
      for (int i = 0; i < N; i++) {
        for (int j = 0; j < S; j++) {
          nurseShiftRoomList[i][j] = new ArrayList<>();
        }
      }
      nurseShiftLoad = new int[N][S];
      roomDayPatientList = (List<Integer>[][]) new List[R][D];
      roomDayAPatients = new int[R][D];
      roomDayBPatients = new int[R][D];
      operatingTheaterDayPatientList = (List<Integer>[][]) new List[T][D];
      operatingTheaterDayLoad = new int[T][D];
      surgeonDayLoad = new int[in.Surgeons()][D];
      surgeonDayTheaterCount = new int[in.Surgeons()][D][in.OperatingTheaters()];
      patientShiftNurse = (List<Integer>[]) new List[P + O];

      // Initialize lists for roomDayPatientList
      for (int r = 0; r < R; r++) {
        for (int d = 0; d < D; d++) {
          roomDayPatientList[r][d] = new ArrayList<>();
        }
      }
      // Initialize lists for operatingTheaterDayPatientList
      for (int t = 0; t < T; t++) {
        for (int d = 0; d < D; d++) {
          operatingTheaterDayPatientList[t][d] = new ArrayList<>();
        }
      }

      // Reset initial state
      reset();

      // Read and parse solution JSON
      BufferedReader br = new BufferedReader(new FileReader(solutionFile));
      JSONTokener tokener = new JSONTokener(br);
      JSONObject j_sol = new JSONObject(tokener);
      br.close();

      // Assign patients from solution
      JSONArray solPatients = j_sol.getJSONArray("patients");
      for (int i = 0; i < solPatients.length(); i++) {
        JSONObject jp = solPatients.getJSONObject(i);
        Object adObj = jp.get("admission_day");
        if (adObj instanceof String && ((String) adObj).equals("none")) {
          continue; // not scheduled
        }
        // scheduled patient
        String pid = jp.getString("id");
        int pIndex = in.findPatientIndex(pid);
        if (isScheduledPatient(pIndex)) {
          throw new IllegalArgumentException("Patient " + pIndex + " assigned twice in the solution");
        }
        int ad = jp.getInt("admission_day");
        String roomId = jp.getString("room");
        int rIndex = in.findRoomIndex(roomId);
        String otId = jp.getString("operating_theater");
        int tIndex = in.findOperatingTheaterIndex(otId);
        assignPatient(pIndex, ad, rIndex, tIndex);
      }

      // Assign nurses from solution
      JSONArray solNurses = j_sol.getJSONArray("nurses");
      for (int k = 0; k < solNurses.length(); k++) {
        JSONObject jn = solNurses.getJSONObject(k);
        String nurseId = jn.getString("id");
        int nurseIndex = in.findNurseIndex(nurseId);
        JSONArray assignments = jn.getJSONArray("assignments");
        for (int a = 0; a < assignments.length(); a++) {
          JSONObject ja = assignments.getJSONObject(a);
          int day = ja.getInt("day");
          String shiftName = ja.getString("shift");
          int shiftIdx = in.findShiftIndex(shiftName);
          int globalShift = day * in.ShiftsPerDay() + shiftIdx;
          JSONArray rooms = ja.getJSONArray("rooms");
          for (int r = 0; r < rooms.length(); r++) {
            int rIndex = in.findRoomIndex(rooms.getString(r));
            assignNurse(nurseIndex, rIndex, globalShift);
          }
        }
      }

      // Add fixed occupants info to schedule
      updateWithOccupantsInfo();
    }

    @SuppressWarnings("unchecked")
    public IHTP_Output(IHTP_Input input, JSONObject j_sol, boolean verbose) throws IOException {
      this.in = input;
      this.VERBOSE = verbose;
      int P = in.Patients(), O = in.Occupants();
      int R = in.Rooms(), N = in.Nurses();
      int T = in.OperatingTheaters(), S = in.Shifts(), D = in.Days();

      // Initialize arrays
      admissionDay = new int[P];
      assignedRoom = new int[P];
      assignedTheater = new int[P];
      roomShiftNurse = new int[R][S];
      nurseShiftRoomList = (List<Integer>[][]) new List[N][S];
      for (int i = 0; i < N; i++) {
        for (int j = 0; j < S; j++) {
          nurseShiftRoomList[i][j] = new ArrayList<>();
        }
      }
      nurseShiftLoad = new int[N][S];
      roomDayPatientList = (List<Integer>[][]) new List[R][D];
      roomDayAPatients = new int[R][D];
      roomDayBPatients = new int[R][D];
      operatingTheaterDayPatientList = (List<Integer>[][]) new List[T][D];
      operatingTheaterDayLoad = new int[T][D];
      surgeonDayLoad = new int[in.Surgeons()][D];
      surgeonDayTheaterCount = new int[in.Surgeons()][D][in.OperatingTheaters()];
      patientShiftNurse = (List<Integer>[]) new List[P + O];

      // Initialize lists for roomDayPatientList
      for (int r = 0; r < R; r++) {
        for (int d = 0; d < D; d++) {
          roomDayPatientList[r][d] = new ArrayList<>();
        }
      }
      // Initialize lists for operatingTheaterDayPatientList
      for (int t = 0; t < T; t++) {
        for (int d = 0; d < D; d++) {
          operatingTheaterDayPatientList[t][d] = new ArrayList<>();
        }
      }

      // Reset initial state
      reset();

      // Assign patients from solution
      JSONArray solPatients = j_sol.getJSONArray("patients");
      for (int i = 0; i < solPatients.length(); i++) {
        JSONObject jp = solPatients.getJSONObject(i);
        Object adObj = jp.get("admission_day");
        if (adObj instanceof String && ((String) adObj).equals("none")) {
          continue; // not scheduled
        }
        // scheduled patient
        String pid = jp.getString("id");
        int pIndex = in.findPatientIndex(pid);
        if (isScheduledPatient(pIndex)) {
          throw new IllegalArgumentException("Patient " + pIndex + " assigned twice in the solution");
        }
        int ad = jp.getInt("admission_day");
        String roomId = jp.getString("room");
        int rIndex = in.findRoomIndex(roomId);
        String otId = jp.getString("operating_theater");
        int tIndex = in.findOperatingTheaterIndex(otId);
        assignPatient(pIndex, ad, rIndex, tIndex);
      }

      // Assign nurses from solution
      JSONArray solNurses = j_sol.getJSONArray("nurses");
      for (int k = 0; k < solNurses.length(); k++) {
        JSONObject jn = solNurses.getJSONObject(k);
        String nurseId = jn.getString("id");
        int nurseIndex = in.findNurseIndex(nurseId);
        JSONArray assignments = jn.getJSONArray("assignments");
        for (int a = 0; a < assignments.length(); a++) {
          JSONObject ja = assignments.getJSONObject(a);
          int day = ja.getInt("day");
          String shiftName = ja.getString("shift");
          int shiftIdx = in.findShiftIndex(shiftName);
          int globalShift = day * in.ShiftsPerDay() + shiftIdx;
          JSONArray rooms = ja.getJSONArray("rooms");
          for (int r = 0; r < rooms.length(); r++) {
            int rIndex = in.findRoomIndex(rooms.getString(r));
            assignNurse(nurseIndex, rIndex, globalShift);
          }
        }
      }

      // Add fixed occupants info to schedule
      updateWithOccupantsInfo();
    }

    @SuppressWarnings("unchecked")
    void reset() {
      // Reset patient assignments
      for (int p = 0; p < in.Patients(); p++) {
        admissionDay[p] = -1;
        assignedRoom[p] = -1;
        assignedTheater[p] = -1;
      }
      // Reset nurse assignments
      for (int r = 0; r < in.Rooms(); r++) {
        Arrays.fill(roomShiftNurse[r], -1);
      }
      for (int n = 0; n < in.Nurses(); n++) {
        for (int s = 0; s < in.Shifts(); s++) {
          nurseShiftRoomList[n][s].clear();
          nurseShiftLoad[n][s] = 0;
        }
      }
      // Reset room occupancy
      for (int r = 0; r < in.Rooms(); r++) {
        for (int d = 0; d < in.Days(); d++) {
          roomDayPatientList[r][d].clear();
          roomDayAPatients[r][d] = 0;
          roomDayBPatients[r][d] = 0;
        }
      }
      // Reset operating theater usage
      for (int t = 0; t < in.OperatingTheaters(); t++) {
        for (int d = 0; d < in.Days(); d++) {
          operatingTheaterDayPatientList[t][d].clear();
          operatingTheaterDayLoad[t][d] = 0;
        }
      }
      // Reset surgeon usage
      for (int s = 0; s < in.Surgeons(); s++) {
        for (int d = 0; d < in.Days(); d++) {
          surgeonDayLoad[s][d] = 0;
          Arrays.fill(surgeonDayTheaterCount[s][d], 0);
        }
      }
      // Reset patient_shift_nurse for all patients and occupants
      for (int p = 0; p < in.Patients() + in.Occupants(); p++) {
        if (p < in.Patients()) {
          patientShiftNurse[p] = new ArrayList<>();
          for (int s = 0; s < in.PatientLengthOfStay(p) * in.ShiftsPerDay(); s++) {
            patientShiftNurse[p].add(-1);
          }
        } else {
          int occIndex = p - in.Patients();
          patientShiftNurse[p] = new ArrayList<>();
          for (int s = 0; s < in.OccupantLengthOfStay(occIndex) * in.ShiftsPerDay(); s++) {
            patientShiftNurse[p].add(-1);
          }
        }
      }
    }

    void assignPatient(int p, int ad, int r, int t) {
      admissionDay[p] = ad;
      assignedRoom[p] = r;
      assignedTheater[p] = t;
      // Assign to room for each day of stay (within horizon)
      for (int d = ad; d < Math.min(in.Days(), ad + in.PatientLengthOfStay(p)); d++) {
        roomDayPatientList[r][d].add(p);
        if (in.PatientGender(p) == IHTP_Input.Gender.A) {
          roomDayAPatients[r][d]++;
        } else {
          roomDayBPatients[r][d]++;
        }
        // If nurse already assigned to this room and day, assign nurse to patient
        for (int s = d * in.ShiftsPerDay(); s < (d + 1) * in.ShiftsPerDay(); s++) {
          int nurse = roomShiftNurse[r][s];
          if (nurse != -1) {
            int relShift = s - ad * in.ShiftsPerDay();
            patientShiftNurse[p].set(relShift, nurse);
            nurseShiftLoad[nurse][s] += in.PatientWorkloadProduced(p, relShift);
          }
        }
      }
      // Assign to operating theater on admission day (surgery day)
      operatingTheaterDayPatientList[t][ad].add(p);
      operatingTheaterDayLoad[t][ad] += in.PatientSurgeryDuration(p);
      int surg = in.PatientSurgeon(p);
      surgeonDayLoad[surg][ad] += in.PatientSurgeryDuration(p);
      surgeonDayTheaterCount[surg][ad][t] += 1;
    }

    void assignNurse(int n, int r, int s) {
      if (!in.IsNurseWorkingInShift(n, s)) {
        throw new IllegalArgumentException(
            "Assigning a non-working nurse " + n + " to shift " + in.ShiftDescription(s));
      }
      roomShiftNurse[r][s] = n;
      nurseShiftRoomList[n][s].add(r);
      int day = s / in.ShiftsPerDay();
      // Assign nurse to all occupants in that room/day
      for (int p : roomDayPatientList[r][day]) {
        if (p < in.Patients()) {
          // patient
          if (admissionDay[p] != -1) {
            int relShift = s - admissionDay[p] * in.ShiftsPerDay();
            nurseShiftLoad[n][s] += in.PatientWorkloadProduced(p, relShift);
            patientShiftNurse[p].set(relShift, n);
          }
        } else {
          // occupant
          int occIndex = p - in.Patients();
          nurseShiftLoad[n][s] += in.OccupantWorkloadProduced(occIndex, s);
          if (s < patientShiftNurse[p].size()) {
            patientShiftNurse[p].set(s, n);
          }
        }
      }
    }

    void updateWithOccupantsInfo() {
      int offset = in.Patients(); // Offset for occupants
      for (int i = 0; i < in.Occupants(); i++) {
        int globalIndex = i + offset;
        int roomIdx = in.OccupantRoom(i);

        // Assign occupants to rooms and nurses
        for (int d = 0; d < in.OccupantLengthOfStay(i); d++) {
          if (d >= in.Days())
            break; // Ignore beyond scheduling horizon

          roomDayPatientList[roomIdx][d].add(globalIndex); // Assign occupant to room
          if (in.OccupantGender(i) == IHTP_Input.Gender.A) {
            roomDayAPatients[roomIdx][d]++;
          } else {
            roomDayBPatients[roomIdx][d]++;
          }

          // Assign nurses to occupants based on room shifts
          for (int s = d * in.ShiftsPerDay(); s < (d + 1) * in.ShiftsPerDay(); s++) {
            int nurse = roomShiftNurse[roomIdx][s];
            if (nurse != -1) {
              int relShift = s; // Relative shift index
              if (relShift < patientShiftNurse[globalIndex].size()) {
                patientShiftNurse[globalIndex].set(relShift, nurse);
              }
            }
          }
        }
      }
    }

    boolean isScheduledPatient(int p) {
      return admissionDay[p] != -1;
    }

    // Hard constraint violations:

    int roomGenderMix() {
      int count = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int d = 0; d < in.Days(); d++) {
          if (roomDayAPatients[r][d] > 0 && roomDayBPatients[r][d] > 0) {
            count += Math.min(roomDayAPatients[r][d], roomDayBPatients[r][d]);
            if (VERBOSE) {
              System.out.println("Room " + in.RoomId(r) + " is gender-mixed " + roomDayAPatients[r][d] + "/"
                  + roomDayBPatients[r][d] + " on day " + d);
            }
          }
        }
      }
      return count;
    }

    int patientRoomCompatibility() {
      int count = 0;
      for (int p = 0; p < in.Patients(); p++) {
        if (assignedRoom[p] != -1 && in.IncompatibleRoom(p, assignedRoom[p])) {
          count++;
          if (VERBOSE) {
            System.out
                .println("Room " + in.RoomId(assignedRoom[p]) + " is incompatible with patient " + in.PatientId(p));
          }
        }
      }
      return count;
    }

    int surgeonOvertime() {
      int count = 0;
      for (int s = 0; s < in.Surgeons(); s++) {
        for (int d = 0; d < in.Days(); d++) {
          if (surgeonDayLoad[s][d] > in.SurgeonMaxSurgeryTime(s, d)) {
            int over = surgeonDayLoad[s][d] - in.SurgeonMaxSurgeryTime(s, d);
            count += over;
            if (VERBOSE) {
              System.out.println("Surgeon " + in.SurgeonId(s) + " has " + over + " minutes of overtime on day " + d);
            }
          }
        }
      }
      return count;
    }

    int operatingTheaterOvertime() {
      int count = 0;
      for (int t = 0; t < in.OperatingTheaters(); t++) {
        for (int d = 0; d < in.Days(); d++) {
          if (operatingTheaterDayLoad[t][d] > in.OperatingTheaterAvailability(t, d)) {
            int over = operatingTheaterDayLoad[t][d] - in.OperatingTheaterAvailability(t, d);
            count += over;
            if (VERBOSE) {
              System.out.println("Operating theaters " + in.OperatingTheaterId(t) + " has " + over
                  + " minutes of overtime on day " + d);
            }
          }
        }
      }
      return count;
    }

    int mandatoryUnscheduledPatients() {
      int count = 0;
      for (int p = 0; p < in.Patients(); p++) {
        if (admissionDay[p] == -1 && in.PatientMandatory(p)) {
          count++;
          if (VERBOSE) {
            System.out.println("Mandatory patient " + in.PatientId(p) + " is unscheduled");
          }
        }
      }
      return count;
    }

    int admissionDayViolations() {
      int count = 0;
      for (int p = 0; p < in.Patients(); p++) {
        if (admissionDay[p] != -1) {
          if (admissionDay[p] < in.PatientSurgeryReleaseDay(p) || admissionDay[p] > in.PatientLastPossibleDay(p)) {
            count++;
            if (VERBOSE) {
              if (admissionDay[p] < in.PatientSurgeryReleaseDay(p)) {
                System.out.println("Patient " + in.PatientId(p) + " is admitted at " + admissionDay[p]
                    + " before the release date " + in.PatientSurgeryReleaseDay(p));
              } else {
                System.out.println("Patient " + in.PatientId(p) + " is admitted at " + admissionDay[p]
                    + " after the last possible date " + in.PatientLastPossibleDay(p));
              }
            }
          }
        }
      }
      return count;
    }

    int roomCapacityViolations() {
      int count = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int d = 0; d < in.Days(); d++) {
          int occNum = roomDayPatientList[r][d].size();
          if (occNum > in.RoomCapacity(r)) {
            count += (occNum - in.RoomCapacity(r));
            if (VERBOSE) {
              System.out.println(
                  "Room " + in.RoomId(r) + " is overloaded by " + (occNum - in.RoomCapacity(r)) + " on day " + d);
            }
          }
        }
      }
      return count;
    }

    int nursePresenceViolations() {
      int count = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int s = 0; s < in.Shifts(); s++) {
          int nurse = roomShiftNurse[r][s];
          if (nurse != -1 && !in.IsNurseWorkingInShift(nurse, s)) {
            count++;
            if (VERBOSE) {
              int d = s / in.ShiftsPerDay();
              int sh = s % in.ShiftsPerDay();
              System.out.println(
                  "Nurse " + in.NurseId(nurse) + " assigned in a non-working shift: day" + d + in.ShiftName(sh));
            }
          }
        }
      }
      return count;
    }

    int uncoveredRoomViolations() {
      int count = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int s = 0; s < in.Shifts(); s++) {
          int d = s / in.ShiftsPerDay();
          if (roomShiftNurse[r][s] == -1 && roomDayPatientList[r][d].size() > 0) {
            count++;
            if (VERBOSE) {
              System.out.println("Room " + in.RoomId(r) + " is uncovered in shift " + in.ShiftDescription(s));
            }
          }
        }
      }
      return count;
    }

    // Soft cost components:

    int roomAgeMixCost() {
      int cost = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int d = 0; d < in.Days(); d++) {
          if (roomDayPatientList[r][d].isEmpty())
            continue;
          int minAge = Integer.MAX_VALUE, maxAge = Integer.MIN_VALUE;
          for (int p : roomDayPatientList[r][d]) {
            int age;
            if (p < in.Patients())
              age = in.PatientAgeGroup(p);
            else
              age = in.OccupantAgeGroup(p - in.Patients());
            if (age < minAge)
              minAge = age;
            if (age > maxAge)
              maxAge = age;
          }
          if (maxAge > minAge) {
            cost += (maxAge - minAge);
            if (VERBOSE) {
              System.out.println("Room " + in.RoomId(r) + " is age-mixed " + minAge + "/" + maxAge + " on day " + d);
            }
          }
        }
      }
      return cost;
    }

    int roomSkillLevelCost() {
      int cost = 0;
      for (int r = 0; r < in.Rooms(); r++) {
        for (int s = 0; s < in.Shifts(); s++) {
          int nurse = roomShiftNurse[r][s];
          if (nurse == -1)
            continue;
          int day = s / in.ShiftsPerDay();
          for (int p : roomDayPatientList[r][day]) {
            if (p < in.Patients()) {
              if (admissionDay[p] == -1)
                continue;
              int relShift = s - admissionDay[p] * in.ShiftsPerDay();
              if (in.PatientSkillLevelRequired(p, relShift) > in.NurseSkillLevel(nurse)) {
                cost += (in.PatientSkillLevelRequired(p, relShift) - in.NurseSkillLevel(nurse));
                if (VERBOSE) {
                  System.out.println("Nurse " + in.NurseId(nurse) + " is underqualified for patient " + in.PatientId(p)
                      + " in room " + in.RoomId(r) + " in shift " + in.ShiftDescription(s));
                }
              }
            } else {
              int occ = p - in.Patients();
              if (in.OccupantSkillLevelRequired(occ, s) > in.NurseSkillLevel(nurse)) {
                cost += (in.OccupantSkillLevelRequired(occ, s) - in.NurseSkillLevel(nurse));
                if (VERBOSE) {
                  System.out.println("Nurse " + in.NurseId(nurse) + " is underqualified for occupant "
                      + in.OccupantId(occ) + " in room " + in.RoomId(r) + " in shift " + in.ShiftDescription(s));
                }
              }
            }
          }
        }
      }
      return cost;
    }

    private int countDistinctNurses(int p) {
      int count = 0;
      boolean[] tag = new boolean[in.Nurses()];
      int st = patientShiftNurse[p].size();
      int maxShifts = Math.min(st, (in.Days() - admissionDay[p]) * in.ShiftsPerDay());

      for (int s = 0; s < maxShifts; s++) {
        int n = patientShiftNurse[p].get(s);
        if (n != -1 && !tag[n]) {
          tag[n] = true;
          count++;
        }
      }
      return count;
    }

    private int countOccupantNurses(int o) {
      int count = 0;
      boolean[] tag = new boolean[in.Nurses()];
      int maxShifts = in.OccupantLengthOfStay(o) * in.ShiftsPerDay();

      for (int s = 0; s < maxShifts; s++) {
        int n = patientShiftNurse[o + in.Patients()].get(s);
        if (n != -1 && !tag[n]) {
          tag[n] = true;
          count++;
        }
      }
      return count;
    }

    int continuityOfCareCost() {
      int cost = 0;

      // Occupants
      for (int o = 0; o < in.Occupants(); o++) {
        int count = countOccupantNurses(o);
        if (count > 0) {
          cost += count;
          if (VERBOSE) {
            System.out.println(count + " distinct nurses for occupant " + in.OccupantId(o));
          }
        }
      }

      // Patients
      for (int p = 0; p < in.Patients(); p++) {
        if (admissionDay[p] == -1)
          continue;
        int count = countDistinctNurses(p);
        if (count > 0) {
          cost += count;
          if (VERBOSE) {
            System.out.println(count + " distinct nurses for patient " + in.PatientId(p));
          }
        }
      }

      return cost;
    }

    int excessiveNurseWorkloadCost() {
      int cost = 0;
      for (int n = 0; n < in.Nurses(); n++) {
        for (int k = 0; k < in.NurseWorkingShifts(n); k++) {
          int s = in.NurseWorkingShift(n, k);
          int day = s / in.ShiftsPerDay();
          // calculate total load manually for this nurse and shift
          int load = 0;
          for (int r : nurseShiftRoomList[n][s]) {
            for (int p : roomDayPatientList[r][day]) {
              if (p < in.Patients()) {
                if (admissionDay[p] == -1)
                  continue;
                int relShift = s - admissionDay[p] * in.ShiftsPerDay();
                load += in.PatientWorkloadProduced(p, relShift);
              } else {
                int occ = p - in.Patients();
                load += in.OccupantWorkloadProduced(occ, s);
              }
            }
          }
          if (load > in.NurseMaxLoad(n, s)) {
            int excess = load - in.NurseMaxLoad(n, s);
            cost += excess;
            if (VERBOSE) {
              System.out.println(
                  "Nurse " + in.NurseId(n) + " has " + excess + " excess workload in shift " + in.ShiftDescription(s));
            }
          }
        }
      }
      return cost;
    }

    int openOperatingRoomCost() {
      int cost = 0;
      for (int t = 0; t < in.OperatingTheaters(); t++) {
        for (int d = 0; d < in.Days(); d++) {
          if (!operatingTheaterDayPatientList[t][d].isEmpty()) {
            cost++;
            if (VERBOSE) {
              System.out.println("Operating theater " + in.OperatingTheaterId(t) + " is open on day " + d);
            }
          }
        }
      }
      return cost;
    }

    int surgeonTransferCost() {
      int cost = 0;
      for (int s = 0; s < in.Surgeons(); s++) {
        for (int d = 0; d < in.Days(); d++) {
          int theaterCount = 0;
          for (int t = 0; t < in.OperatingTheaters(); t++) {
            if (surgeonDayTheaterCount[s][d][t] > 0)
              theaterCount++;
          }
          if (theaterCount > 1) {
            cost += (theaterCount - 1);
            if (VERBOSE) {
              System.out.println(
                  "Surgeon " + in.SurgeonId(s) + " operates in " + theaterCount + " distinct operating theaters");
            }
          }
        }
      }
      return cost;
    }

    int patientDelayCost() {
      int cost = 0;
      for (int p = 0; p < in.Patients(); p++) {
        if (admissionDay[p] != -1 && admissionDay[p] > in.PatientSurgeryReleaseDay(p)) {
          int delay = admissionDay[p] - in.PatientSurgeryReleaseDay(p);
          cost += delay;
          if (VERBOSE) {
            System.out.println("Patient " + in.PatientId(p) + " has been delayed for " + delay + " days");
          }
        }
      }
      return cost;
    }

    int electiveUnscheduledPatientsCost() {
      int cost = 0;
      for (int p = 0; p < in.Patients(); p++) {
        if (admissionDay[p] == -1 && !in.PatientMandatory(p)) {
          cost++;
          if (VERBOSE) {
            System.out.println("Elective patient " + in.PatientId(p) + " is unscheduled");
          }
        }
      }
      return cost;
    }
  }

  // Helper: cetak daftar ID pasien mandatory yang belum terjadwal
  private static void printUnscheduledMandatory(IHTP_Input input, IHTP_Output output) {
    System.out.println();
    System.out.println("UNSCHEDULED MANDATORY PATIENTS:");
    int printed = 0;
    for (int p = 0; p < input.Patients(); p++) {
      if (output.admissionDay[p] == -1 && input.PatientMandatory(p)) {
        System.out.println("- " + input.PatientId(p));
        printed++;
      }
    }
    if (printed == 0) {
      System.out.println("(none)");
    }
  }

  // buat object input sebagai parameter constructor
  public IHTP_Validator(IHTP_Input input, JSONObject solution, boolean verbose) {
    try {
      IHTP_Output output = new IHTP_Output(input, solution, false);

      // Compute violations
      vRoomGender = output.roomGenderMix();
      vRoomCompat = output.patientRoomCompatibility();
      vSurgeonOver = output.surgeonOvertime();
      vOTOver = output.operatingTheaterOvertime();
      vMandatoryUnsch = output.mandatoryUnscheduledPatients();
      vAdmissionDay = output.admissionDayViolations();
      vRoomCap = output.roomCapacityViolations();
      vNursePres = output.nursePresenceViolations();
      vUncovered = output.uncoveredRoomViolations();
      totalViolations = vRoomGender + vRoomCompat + vSurgeonOver + vOTOver + vAdmissionDay + vRoomCap + vNursePres
          + vUncovered + vMandatoryUnsch;

      // Compute costs
      int cRoomAge = output.roomAgeMixCost();
      int cRoomSkill = output.roomSkillLevelCost();
      int cContinuity = output.continuityOfCareCost();
      int cExcessive = output.excessiveNurseWorkloadCost();
      int cOpenOT = output.openOperatingRoomCost();
      int cTransfer = output.surgeonTransferCost();
      int cDelay = output.patientDelayCost();
      int cElectiveUnsch = output.electiveUnscheduledPatientsCost();
      totalCost = (long) cRoomAge * input.weights[0] + (long) cRoomSkill * input.weights[1]
          + (long) cContinuity * input.weights[2] + (long) cExcessive * input.weights[3]
          + (long) cOpenOT * input.weights[4] + (long) cTransfer * input.weights[5] + (long) cDelay * input.weights[6]
          + (long) cElectiveUnsch * input.weights[7];

      // Print results in required format
      if (verbose) {

        System.out.println("VIOLATIONS:");
        printViolationLine("MandatoryUnscheduledPatients", vMandatoryUnsch);
        printViolationLine("RoomGenderMix", vRoomGender);
        printViolationLine("PatientRoomCompatibility", vRoomCompat);
        printViolationLine("SurgeonOvertime", vSurgeonOver);
        printViolationLine("OperatingTheaterOvertime", vOTOver);
        printViolationLine("AdmissionDay", vAdmissionDay);
        printViolationLine("RoomCapacity", vRoomCap);
        printViolationLine("NursePresence", vNursePres);
        printViolationLine("UncoveredRoom", vUncovered);
        System.out.println("Total violations = " + totalViolations);
        System.out.println();
        System.out.println("COSTS (weight x cost):");
        printCostLine("RoomAgeMix", cRoomAge, input.weights[0]);
        printCostLine("RoomSkillLevel", cRoomSkill, input.weights[1]);
        printCostLine("ContinuityOfCare", cContinuity, input.weights[2]);
        printCostLine("ExcessiveNurseWorkload", cExcessive, input.weights[3]);
        printCostLine("OpenOperatingRoom", cOpenOT, input.weights[4]);
        printCostLine("SurgeonTransfer", cTransfer, input.weights[5]);
        printCostLine("PatientDelay", cDelay, input.weights[6]);
        printCostLine("ElectiveUnscheduledPatients", cElectiveUnsch, input.weights[7]);
        System.out.println("Total cost = " + totalCost);
      }
    } catch (Exception e) {
      // Print any error (file not found, JSON parse error, invalid ID, etc.)
      System.out.println(e.getMessage());
    }
  }

  public static void main(String[] args) {
    if (args.length < 2 || args.length > 3) {
      System.err.println("Usage: java IHTPValidator <instance_file.json> <solution_file.json> [verbose]");
      return;
    }
    boolean verbose = (args.length == 3 && args[2].equalsIgnoreCase("verbose"));
    try {
      IHTP_Input input = new IHTP_Input(args[0]);
      IHTP_Output output = new IHTP_Output(input, args[1], verbose);

      // Compute violations
      vRoomGender = output.roomGenderMix();
      vRoomCompat = output.patientRoomCompatibility();
      vSurgeonOver = output.surgeonOvertime();
      vOTOver = output.operatingTheaterOvertime();
      vMandatoryUnsch = output.mandatoryUnscheduledPatients();
      vAdmissionDay = output.admissionDayViolations();
      vRoomCap = output.roomCapacityViolations();
      vNursePres = output.nursePresenceViolations();
      vUncovered = output.uncoveredRoomViolations();
      totalViolations = vRoomGender + vRoomCompat + vSurgeonOver + vOTOver + vAdmissionDay + vRoomCap + vNursePres
          + vUncovered + vMandatoryUnsch;

      // Compute costs
      int cRoomAge = output.roomAgeMixCost();
      int cRoomSkill = output.roomSkillLevelCost();
      int cContinuity = output.continuityOfCareCost();
      int cExcessive = output.excessiveNurseWorkloadCost();
      int cOpenOT = output.openOperatingRoomCost();
      int cTransfer = output.surgeonTransferCost();
      int cDelay = output.patientDelayCost();
      int cElectiveUnsch = output.electiveUnscheduledPatientsCost();
      long totalCost = (long) cRoomAge * input.weights[0] + (long) cRoomSkill * input.weights[1]
          + (long) cContinuity * input.weights[2] + (long) cExcessive * input.weights[3]
          + (long) cOpenOT * input.weights[4] + (long) cTransfer * input.weights[5] + (long) cDelay * input.weights[6]
          + (long) cElectiveUnsch * input.weights[7];

      // Print results in required format
      System.out.println("VIOLATIONS:");
      printViolationLine("MandatoryUnscheduledPatients", vMandatoryUnsch);
      printViolationLine("RoomGenderMix", vRoomGender);
      printViolationLine("PatientRoomCompatibility", vRoomCompat);
      printViolationLine("SurgeonOvertime", vSurgeonOver);
      printViolationLine("OperatingTheaterOvertime", vOTOver);
      printViolationLine("AdmissionDay", vAdmissionDay);
      printViolationLine("RoomCapacity", vRoomCap);
      printViolationLine("NursePresence", vNursePres);
      printViolationLine("UncoveredRoom", vUncovered);
      System.out.println("Total violations = " + totalViolations);
      System.out.println();
      System.out.println("COSTS (weight x cost):");
      printCostLine("RoomAgeMix", cRoomAge, input.weights[0]);
      printCostLine("RoomSkillLevel", cRoomSkill, input.weights[1]);
      printCostLine("ContinuityOfCare", cContinuity, input.weights[2]);
      printCostLine("ExcessiveNurseWorkload", cExcessive, input.weights[3]);
      printCostLine("OpenOperatingRoom", cOpenOT, input.weights[4]);
      printCostLine("SurgeonTransfer", cTransfer, input.weights[5]);
      printCostLine("PatientDelay", cDelay, input.weights[6]);
      printCostLine("ElectiveUnscheduledPatients", cElectiveUnsch, input.weights[7]);
      System.out.println("Total cost = " + totalCost);

      // Tambahan: selalu cetak daftar mandatory yang belum terjadwal saat via CLI
      printUnscheduledMandatory(input, output);
    } catch (Exception e) {
      // Print any error (file not found, JSON parse error, invalid ID, etc.)
      System.out.println(e.getMessage());
    }
  }

  // Helper to print a violations line with dot alignment
  private static void printViolationLine(String name, int value) {
    StringBuilder sb = new StringBuilder();
    sb.append(name).append(":");
    String valueStr = String.valueOf(value);
    // Align all violation numbers in same column (approx column 30)
    int dotsCount = 30 - name.length() - 1 - valueStr.length();
    if (dotsCount < 0)
      dotsCount = 0;
    for (int i = 0; i < dotsCount; i++)
      sb.append(".");
    sb.append(valueStr);
    System.out.println(sb);
  }

  // Helper to print a cost line with weight and cost in parentheses
  private static void printCostLine(String name, int cost, int weight) {
    StringBuilder sb = new StringBuilder();
    sb.append(name).append(":");
    long weightedValue = (long) weight * cost;
    String weightedStr = String.valueOf(weightedValue);
    int dotsCount = 30 - name.length() - 1 - weightedStr.length();
    if (dotsCount < 0)
      dotsCount = 0;
    for (int i = 0; i < dotsCount; i++)
      sb.append(".");
    sb.append(weightedStr).append(" (").append(weight).append(" x ").append(cost).append(")");
    System.out.println(sb);
  }

  public int getVRoomGender() {
    return vRoomGender;
  }

  public int getVRoomCompat() {
    return vRoomCompat;
  }

  public int getVSurgeonOver() {
    return vSurgeonOver;
  }

  public int getVOTOver() {
    return vOTOver;
  }

  public int getVMandatoryUnsch() {
    return vMandatoryUnsch;
  }

  public int getVAdmissionDay() {
    return vAdmissionDay;
  }

  public int getVRoomCap() {
    return vRoomCap;
  }

  public int getVNursePres() {
    return vNursePres;
  }

  public int getVUncovered() {
    return vUncovered;
  }

  public int getTotalViolations() {
    return totalViolations;
  }

  public long getTotalCost() {
    return totalCost;
  }
}