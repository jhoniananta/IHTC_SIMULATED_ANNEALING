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

public class IHTP_Input {

    enum Gender {
        A, B
    }

    // Occupant base class
    static class Occupant {
        String id;
        Gender gender;
        int ageGroup;
        int lengthOfStay;
        int assignedRoom; // index dalam roomsList untuk precomputed
        int[] workloadProduced;
        int[] skillLevelRequired;
    }

    // Patient class extends Occupant
    static class Patient extends Occupant {
        boolean mandatory;
        int surgeryReleaseDay;
        int surgeryDueDay;
        int surgeon; // index dalam surgeonsList
        int surgeryDuration;
        boolean[] incompatibleRooms;
        Integer maxSlot = null;
    }

    static class Surgeon {
        String id;
        int[] maxSurgeryTime; // per day
    }

    static class OperatingTheater {
        String id;
        int[] availability; // per day
    }

    static class Room {
        String id;
        int capacity;
    }

    static class Nurse {
        String id;
        int skillLevel;
        boolean[] isWorkingInShift;
        int[] maxLoads;
        List<Integer> workingShifts;
    }

    // Input data
    int days;
    int skillLevels;
    int ageGroups;
    int shiftsPerDay;
    int shifts; // days * shiftsPerDay
    String[] shiftNames;
    String[] ageGroupNames;

    // Soft cost weights (index 0-7 corresponds to cost components in order)
    int[] weights = new int[8];

    List<Occupant> occupantsList = new ArrayList<>();
    List<Patient> patientsList = new ArrayList<>();
    List<Surgeon> surgeonsList = new ArrayList<>();
    List<OperatingTheater> theatersList = new ArrayList<>();
    List<Room> roomsList = new ArrayList<>();
    List<Nurse> nursesList = new ArrayList<>();

    int occupantsCount, patientsCount, surgeonsCount, theatersCount, roomsCount, nursesCount;

    // Maps for quick lookup
    Map<String, Integer> occupantIndexById = new HashMap<>();
    Map<String, Integer> patientIndexById = new HashMap<>();
    Map<String, Integer> surgeonIndexById = new HashMap<>();
    Map<String, Integer> theaterIndexById = new HashMap<>();
    Map<String, Integer> roomIndexById = new HashMap<>();
    Map<String, Integer> nurseIndexById = new HashMap<>();
    Map<String, Integer> shiftIndexByName = new HashMap<>();

    public IHTP_Input(String fileName) throws IOException {
        // Read and parse JSON file
        BufferedReader br = new BufferedReader(new FileReader(fileName));
        JSONTokener tokener = new JSONTokener(br);
        JSONObject j_in = new JSONObject(tokener);
        br.close();

        // General info and weights
        JSONObject w = j_in.getJSONObject("weights");
        weights[0] = w.getInt("room_mixed_age");
        weights[1] = w.getInt("room_nurse_skill");
        weights[2] = w.getInt("continuity_of_care");
        weights[3] = w.getInt("nurse_eccessive_workload");
        weights[4] = w.getInt("open_operating_theater");
        weights[5] = w.getInt("surgeon_transfer");
        weights[6] = w.getInt("patient_delay");
        weights[7] = w.getInt("unscheduled_optional");

        days = j_in.getInt("days");
        skillLevels = j_in.getInt("skill_levels");

        // Shifts per day
        JSONArray shiftTypes = j_in.getJSONArray("shift_types");
        shiftsPerDay = shiftTypes.length();
        shifts = days * shiftsPerDay;
        shiftNames = new String[shiftsPerDay];
        for (int i = 0; i < shiftsPerDay; i++) {
            String sName = shiftTypes.getString(i);
            shiftNames[i] = sName;
            shiftIndexByName.put(sName, i);
        }

        // Age groups
        JSONArray ageTypes = j_in.getJSONArray("age_groups");
        ageGroups = ageTypes.length();
        ageGroupNames = new String[ageGroups];
        for (int i = 0; i < ageGroups; i++) {
            ageGroupNames[i] = ageTypes.getString(i);
        }

        // Entity counts
        occupantsCount = j_in.getJSONArray("occupants").length();
        patientsCount = j_in.getJSONArray("patients").length();
        surgeonsCount = j_in.getJSONArray("surgeons").length();
        theatersCount = j_in.getJSONArray("operating_theaters").length();
        roomsCount = j_in.getJSONArray("rooms").length();
        nursesCount = j_in.getJSONArray("nurses").length();
        // Parse surgeons
        JSONArray jSurgeons = j_in.getJSONArray("surgeons");
        for (int s = 0; s < surgeonsCount; s++) {
            JSONObject js = jSurgeons.getJSONObject(s);
            Surgeon sur = new Surgeon();
            sur.id = js.getString("id");
            sur.maxSurgeryTime = new int[days];
            JSONArray maxTime = js.getJSONArray("max_surgery_time");
            if (maxTime.length() != days) {
                throw new IllegalArgumentException("Surgeon " + sur.id + " max_surgery_time length mismatch");
            }
            for (int d = 0; d < days; d++) {
                sur.maxSurgeryTime[d] = maxTime.getInt(d) * 60;
            }
            surgeonIndexById.put(sur.id, surgeonsList.size());
            surgeonsList.add(sur);
        }

        // Parse operating theaters
        JSONArray jTheaters = j_in.getJSONArray("operating_theaters");
        for (int t = 0; t < theatersCount; t++) {
            JSONObject jt = jTheaters.getJSONObject(t);
            OperatingTheater ot = new OperatingTheater();
            ot.id = jt.getString("id");
            ot.availability = new int[days];
            JSONArray avail = jt.getJSONArray("availability");
            if (avail.length() != days) {
                throw new IllegalArgumentException("Operating theater " + ot.id + " availability length mismatch");
            }
            for (int d = 0; d < days; d++) {
                ot.availability[d] = avail.getInt(d) * 60;
            }
            theaterIndexById.put(ot.id, theatersList.size());
            theatersList.add(ot);
        }

        // Parse rooms
        JSONArray jRooms = j_in.getJSONArray("rooms");
        for (int r = 0; r < roomsCount; r++) {
            JSONObject jr = jRooms.getJSONObject(r);
            Room room = new Room();
            room.id = jr.getString("id");
            room.capacity = jr.getInt("capacity");
            roomIndexById.put(room.id, roomsList.size());
            roomsList.add(room);
        }

        // Parse occupants (already present patients)
        JSONArray jOccupants = j_in.getJSONArray("occupants");
        for (int f = 0; f < occupantsCount; f++) {
            JSONObject jf = jOccupants.getJSONObject(f);
            Occupant occ = new Occupant();
            occ.id = jf.getString("id");
            occ.gender = jf.getString("gender").equals("A") ? Gender.A : Gender.B;
            occ.ageGroup = findAgeGroupIndex(jf.getString("age_group"));
            occ.lengthOfStay = jf.getInt("length_of_stay");
            occ.workloadProduced = new int[occ.lengthOfStay * shiftsPerDay];
            occ.skillLevelRequired = new int[occ.lengthOfStay * shiftsPerDay];
            JSONArray workArr = jf.getJSONArray("workload_produced");
            JSONArray skillArr = jf.getJSONArray("skill_level_required");
            if (workArr.length() != occ.workloadProduced.length || skillArr.length() != occ.skillLevelRequired.length) {
                throw new IllegalArgumentException("Occupant " + occ.id + " workload/skill array length mismatch");
            }
            for (int s = 0; s < occ.workloadProduced.length; s++) {
                occ.workloadProduced[s] = workArr.getInt(s) * 60;
                occ.skillLevelRequired[s] = skillArr.getInt(s);
            }
            occ.assignedRoom = findRoomIndex(jf.getString("room_id"));
            occupantIndexById.put(occ.id, occupantsList.size());
            occupantsList.add(occ);
        }

        // Parse patients
        JSONArray jPatients = j_in.getJSONArray("patients");
        for (int p = 0; p < patientsCount; p++) {
            JSONObject jp = jPatients.getJSONObject(p);
            Patient pat = new Patient();
            pat.id = jp.getString("id");
            pat.mandatory = jp.getBoolean("mandatory");
            pat.gender = jp.getString("gender").equals("A") ? Gender.A : Gender.B;
            pat.ageGroup = findAgeGroupIndex(jp.getString("age_group"));
            pat.lengthOfStay = jp.getInt("length_of_stay");
            pat.surgeryReleaseDay = jp.getInt("surgery_release_day");
            if (pat.mandatory) {
                pat.surgeryDueDay = jp.getInt("surgery_due_day");
            } else {
                pat.surgeryDueDay = -1;
            }
            pat.surgeryDuration = jp.getInt("surgery_duration") * 60;
            pat.surgeon = findSurgeonIndex(jp.getString("surgeon_id"));
            // Incompatible rooms
            pat.incompatibleRooms = new boolean[roomsCount];
            if (jp.has("incompatible_room_ids") && !jp.isNull("incompatible_room_ids")) {
                JSONArray incompat = jp.getJSONArray("incompatible_room_ids");
                for (int i = 0; i < incompat.length(); i++) {
                    int badRoom = findRoomIndex(incompat.getString(i));
                    pat.incompatibleRooms[badRoom] = true;
                }
            } else {
                Arrays.fill(pat.incompatibleRooms, false);
            }
            // Workload and skill arrays
            pat.workloadProduced = new int[pat.lengthOfStay * shiftsPerDay];
            pat.skillLevelRequired = new int[pat.lengthOfStay * shiftsPerDay];
            JSONArray workArr = jp.getJSONArray("workload_produced");
            JSONArray skillArr = jp.getJSONArray("skill_level_required");
            if (workArr.length() != pat.workloadProduced.length || skillArr.length() != pat.skillLevelRequired.length) {
                throw new IllegalArgumentException("Patient " + pat.id + " workload/skill array length mismatch");
            }
            for (int s = 0; s < pat.workloadProduced.length; s++) {
                pat.workloadProduced[s] = workArr.getInt(s) * 60;
                pat.skillLevelRequired[s] = skillArr.getInt(s);
            }
            patientIndexById.put(pat.id, patientsList.size());
            patientsList.add(pat);
        }

        // Parse nurses
        JSONArray jNurses = j_in.getJSONArray("nurses");
        for (int n = 0; n < nursesCount; n++) {
            JSONObject jn = jNurses.getJSONObject(n);
            Nurse nurse = new Nurse();
            nurse.id = jn.getString("id");
            nurse.skillLevel = jn.getInt("skill_level");
            nurse.isWorkingInShift = new boolean[shifts];
            nurse.maxLoads = new int[shifts];
            Arrays.fill(nurse.maxLoads, 0);
            // Working shifts
            JSONArray workShifts = jn.getJSONArray("working_shifts");
            nurse.workingShifts = new ArrayList<>();
            for (int i = 0; i < workShifts.length(); i++) {
                JSONObject ws = workShifts.getJSONObject(i);
                int day = ws.getInt("day");
                String shiftName = ws.getString("shift");
                int shiftIdx = findShiftIndex(shiftName);
                int globalShift = day * shiftsPerDay + shiftIdx;
                nurse.workingShifts.add(globalShift);
                nurse.isWorkingInShift[globalShift] = true;
                nurse.maxLoads[globalShift] = ws.getInt("max_load") * 60;
            }
            nurseIndexById.put(nurse.id, nursesList.size());
            nursesList.add(nurse);
        }
    }

    // Helper find methods (throw if not found)
    int findAgeGroupIndex(String ageName) {
        for (int i = 0; i < ageGroups; i++) {
            if (ageGroupNames[i].equals(ageName))
                return i;
        }
        throw new IllegalArgumentException("Unknown age group name");
    }

    int findRoomIndex(String roomId) {
        Integer idx = roomIndexById.get(roomId);
        if (idx == null)
            throw new IllegalArgumentException("Unknown room id " + roomId);
        return idx;
    }

    int findOperatingTheaterIndex(String theaterId) {
        Integer idx = theaterIndexById.get(theaterId);
        if (idx == null)
            throw new IllegalArgumentException("Unknown operating theater id " + theaterId);
        return idx;
    }

    int findPatientIndex(String patientId) {
        Integer idx = patientIndexById.get(patientId);
        if (idx == null)
            throw new IllegalArgumentException("Unknown patient id " + patientId);
        return idx;
    }

    int findNurseIndex(String nurseId) {
        Integer idx = nurseIndexById.get(nurseId);
        if (idx == null)
            throw new IllegalArgumentException("Unknown nurse id " + nurseId);
        return idx;
    }

    int findSurgeonIndex(String surgeonId) {
        Integer idx = surgeonIndexById.get(surgeonId);
        if (idx == null)
            throw new IllegalArgumentException("Unknown surgeon id " + surgeonId);
        return idx;
    }

    int findShiftIndex(String shiftName) {
        Integer idx = shiftIndexByName.get(shiftName);
        if (idx == null)
            throw new IllegalArgumentException("Unknown shift name");
        return idx;
    }

    // Quick getters for use in output computations
    int Days() {
        return days;
    }

    int ShiftsPerDay() {
        return shiftsPerDay;
    }

    int Shifts() {
        return shifts;
    }

    int Patients() {
        return patientsCount;
    }

    int Occupants() {
        return occupantsCount;
    }

    int Surgeons() {
        return surgeonsCount;
    }

    int OperatingTheaters() {
        return theatersCount;
    }

    int Rooms() {
        return roomsCount;
    }

    int Nurses() {
        return nursesCount;
    }

    String OperatingTheaterId(int t) {
        return theatersList.get(t).id;
    }

    int OperatingTheaterAvailability(int t, int d) {
        return theatersList.get(t).availability[d];
    }

    String SurgeonId(int s) {
        return surgeonsList.get(s).id;
    }

    int SurgeonMaxSurgeryTime(int s, int d) {
        return surgeonsList.get(s).maxSurgeryTime[d];
    }

    String PatientId(int p) {
        return patientsList.get(p).id;
    }

    boolean PatientMandatory(int p) {
        return patientsList.get(p).mandatory;
    }

    int PatientSurgeryReleaseDay(int p) {
        return patientsList.get(p).surgeryReleaseDay;
    }

    int PatientLastPossibleDay(int p) {
        return patientsList.get(p).mandatory ? patientsList.get(p).surgeryDueDay : days - 1;
    }

    int PatientSurgeon(int p) {
        return patientsList.get(p).surgeon;
    }

    int PatientSurgeryDuration(int p) {
        return patientsList.get(p).surgeryDuration;
    }

    Gender PatientGender(int p) {
        return patientsList.get(p).gender;
    }

    int PatientAgeGroup(int p) {
        return patientsList.get(p).ageGroup;
    }

    boolean IncompatibleRoom(int p, int r) {
        return patientsList.get(p).incompatibleRooms[r];
    }

    int PatientLengthOfStay(int p) {
        return patientsList.get(p).lengthOfStay;
    }

    int PatientWorkloadProduced(int p, int sIndex) {
        return patientsList.get(p).workloadProduced[sIndex];
    }

    int PatientSkillLevelRequired(int p, int sIndex) {
        return patientsList.get(p).skillLevelRequired[sIndex];
    }

    String OccupantId(int o) {
        return occupantsList.get(o).id;
    }

    Gender OccupantGender(int o) {
        return occupantsList.get(o).gender;
    }

    int OccupantAgeGroup(int o) {
        return occupantsList.get(o).ageGroup;
    }

    int OccupantLengthOfStay(int o) {
        return occupantsList.get(o).lengthOfStay;
    }

    int OccupantRoom(int o) {
        return occupantsList.get(o).assignedRoom;
    }

    int OccupantWorkloadProduced(int o, int sIndex) {
        return occupantsList.get(o).workloadProduced[sIndex];
    }

    int OccupantSkillLevelRequired(int o, int sIndex) {
        return occupantsList.get(o).skillLevelRequired[sIndex];
    }

    String RoomId(int r) {
        return roomsList.get(r).id;
    }

    int RoomCapacity(int r) {
        return roomsList.get(r).capacity;
    }

    String NurseId(int n) {
        return nursesList.get(n).id;
    }

    int NurseSkillLevel(int n) {
        return nursesList.get(n).skillLevel;
    }

    boolean IsNurseWorkingInShift(int n, int s) {
        return nursesList.get(n).isWorkingInShift[s];
    }

    int NurseMaxLoad(int n, int s) {
        return nursesList.get(n).maxLoads[s];
    }

    int NurseWorkingShifts(int n) {
        return nursesList.get(n).workingShifts.size();
    }

    int NurseWorkingShift(int n, int k) {
        return nursesList.get(n).workingShifts.get(k);
    }

    String ShiftName(int idx) {
        return shiftNames[idx];
    }

    String ShiftDescription(int globalShift) {
        int d = globalShift / shiftsPerDay;
        int sh = globalShift % shiftsPerDay;
        return "day" + d + shiftNames[sh];
    }

    // Utility function untuk mendapatkan list ID pasien yang belum terjadwal
    // (assignedDay == null)
    public static List<String> getUnscheduledPatientIds(List<IHTP_Solution.OutputPatient> solutionList) {
        List<String> unscheduledPatientIds = new ArrayList<>();
        for (IHTP_Solution.OutputPatient op : solutionList) {
            if (op.assignedDay == null) {
                unscheduledPatientIds.add(op.id);
            }
        }
        return unscheduledPatientIds;
    }
}