
// IHTP_Solution.java
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

public class IHTP_Solution {
  static final AtomicInteger minViolationsSolution = new AtomicInteger(Integer.MAX_VALUE);
  // global flaag untuk solusi optimal
  static final AtomicBoolean optimalSolutionFound = new AtomicBoolean(false);
  static final Object fileLock = new Object();

  static class OutputPatient {
    String id;
    Integer assignedDay = null;
    String assignedRoom = null;
    String assignedTheater = null;
    Integer maxSlot = null;
  }

  static class OutputNurse {
    String id;
    List<Integer> workingShifts = new ArrayList<>();
    List<List<String>> workingRooms = new ArrayList<>();
  }

  static class IHTP_Generate_Solution {
    private final IHTP_Input in;
    // Solution data Patients Assignment
    int totalDays;
    Map<String, int[]> surgeonUsage; // surgeonUsage[surgeonId][day] = minutes used
    Map<String, int[]> theaterUsage; // theaterUsage[theaterId][day] = minutes used
    Map<String, int[]> roomOccupancy; // roomOccupancy[roomId][day] = patient count
    Map<String, IHTP_Input.Gender[]> roomGender; // roomGender[roomId][day] = 'A', 'B', or '\0' if empty

    Map<String, List<IHTP_Input.Patient>[]> patientsInRoomByDay = new HashMap<>(); // room → array[day] → list
    Map<IHTP_Input.Patient, Integer> patientStartDay = new HashMap<>(); // start day index

    List<OutputPatient> outputPatients;
    List<OutputPatient> temporaryAfterPlace;
    List<OutputPatient> tempOutputPatients;
    List<IHTP_Input.Patient> unscheduledPatientsInitial = new ArrayList<>();
    // Solution data Nurses Assignment
    Map<String, String[]> nurseShiftAssignment;
    Map<String, int[]> nurseWorkload;
    Map<String, int[]> nurseSkill;
    Map<String, List<List<String>>> nurseRoomOccupancy;
    List<OutputNurse> outputNurses;
    // Control the number of random attempts per patient (increased for better
    // initial placement)
    int MAX_ITERATIONS = 2000;

    public IHTP_Generate_Solution(IHTP_Input input) {
      this.in = input;
      this.totalDays = in.Days();
      this.surgeonUsage = new HashMap<>();
      this.theaterUsage = new HashMap<>();
      this.roomOccupancy = new HashMap<>();
      this.roomGender = new HashMap<>();
      this.outputPatients = new ArrayList<>();
      this.tempOutputPatients = new ArrayList<>();
      this.temporaryAfterPlace = new ArrayList<>();
      this.nurseShiftAssignment = new HashMap<>();
      this.nurseWorkload = new HashMap<>();
      this.nurseSkill = new HashMap<>();
      this.nurseRoomOccupancy = new HashMap<>();
      this.outputNurses = new ArrayList<>();
      // Initialize solution data
      // allocate usage arrays ------------------------------------------------
      for (IHTP_Input.Surgeon s : in.surgeonsList)
        surgeonUsage.put(s.id, new int[totalDays]);
      for (IHTP_Input.OperatingTheater ot : in.theatersList)
        theaterUsage.put(ot.id, new int[totalDays]);
      for (IHTP_Input.Room r : in.roomsList) {
        roomOccupancy.put(r.id, new int[totalDays]);
        roomGender.put(r.id, new IHTP_Input.Gender[totalDays]);

        // new patientsInRoom structure
        @SuppressWarnings("unchecked")
        List<IHTP_Input.Patient>[] arr = new List[totalDays];
        for (int d = 0; d < totalDays; d++)
          arr[d] = new ArrayList<>();
        patientsInRoomByDay.put(r.id, arr);

        nurseShiftAssignment.put(r.id, new String[totalDays * in.ShiftsPerDay()]);
      }
      for (IHTP_Input.Nurse n : in.nursesList) {
        nurseWorkload.put(n.id, new int[totalDays * in.ShiftsPerDay()]);
        nurseRoomOccupancy.put(n.id, new ArrayList<>());
      }
    }

    /*
     * ------------------------------------------------------------------ HARD
     * CONSTRAINT HELPERS (H1‑H7)
     * ----------------------------------------------------------------
     */
    private boolean genderMix(IHTP_Input.Room room, int day, IHTP_Input.Patient p) {
      for (IHTP_Input.Patient q : patientsInRoomByDay.get(room.id)[day])
        if (q.gender != p.gender)
          return true; // H1
      return false;
    }

    private boolean roomFull(IHTP_Input.Room room, int day) {
      return patientsInRoomByDay.get(room.id)[day].size() >= room.capacity; // H7
    }

    private boolean surgeonOvertime(IHTP_Input.Patient p, int day) {
      IHTP_Input.Surgeon s = in.surgeonsList.get(p.surgeon);
      return surgeonUsage.get(s.id)[day] + p.surgeryDuration > s.maxSurgeryTime[day]; // H3
    }

    private boolean otOvertime(String otId, int day, IHTP_Input.Patient p) {
      int used = theaterUsage.get(otId)[day];
      int cap = in.theatersList.get(in.theaterIndexById.get(otId)).availability[day];
      return used + p.surgeryDuration > cap; // H4
    }

    private boolean dateWindowInvalid(IHTP_Input.Patient p, int start) {
      if (p.mandatory)
        return start < p.surgeryReleaseDay || start > p.surgeryDueDay; // H6 mand.
      return start < p.surgeryReleaseDay; // optional
    }

    public boolean generateInitialSolution(PriorityQueue<IHTP_Input.Patient> patientQueue) {
      // 1. separate mandatory / optional
      // List<IHTP_Input.Patient> mandatory = new ArrayList<>(), optional = new
      // ArrayList<>();
      // for (IHTP_Input.Patient p : in.patientsList)
      // (p.mandatory ? mandatory : optional).add(p);

      // 2. place mandatory patients (retry ordering if needed)
      // if (!placePatients(mandatory, true))
      // return false; // impossible schedule
      // Schedule nurses
      attemptRandomNurseAssignment(1000);
      // Transform solution data to output format
      transformNurseSolution();

      placePatients(patientQueue, true);
      // 3. greedily place optional patients
      // placePatients(optional, false, inputFile);

      List<OutputPatient> swappedPatients = swapPatients();

      // Create set of scheduled patient IDs
      java.util.Set<String> scheduledIds = new java.util.HashSet<>();
      for (OutputPatient out : swappedPatients) {
        OutputPatient p = new OutputPatient();
        p.id = out.id;
        p.assignedDay = out.assignedDay;
        p.assignedRoom = out.assignedRoom;
        p.assignedTheater = out.assignedTheater;
        outputPatients.add(p);
        scheduledIds.add(out.id);
      }

      // Add unscheduled mandatory patients with assignedDay = null
      for (IHTP_Input.Patient unscheduled : unscheduledPatientsInitial) {
        if (!scheduledIds.contains(unscheduled.id)) {
          OutputPatient p = new OutputPatient();
          p.id = unscheduled.id;
          p.assignedDay = null; // Mark as unscheduled
          p.assignedRoom = null;
          p.assignedTheater = null;
          outputPatients.add(p);
        }
      }

      // Prepare output JSON
      JSONObject j_out = new JSONObject();
      JSONArray outPatients = getOutputPatients(outputPatients);
      JSONArray outNurses = getOutputNurses();

      j_out.put("patients", outPatients);
      j_out.put("nurses", outNurses);
      IHTP_Validator validator = new IHTP_Validator(this.in, j_out, true);
      int totalViolations = validator.getTotalViolations();

      return false;
    }

    public JSONArray getOutputPatients(List<OutputPatient> output) {
      JSONArray outPatients = new JSONArray();
      for (OutputPatient out : output) {
        JSONObject pj = new JSONObject();
        pj.put("id", out.id);
        if (out.assignedDay != null) {
          pj.put("admission_day", out.assignedDay);
          pj.put("room", out.assignedRoom);
          pj.put("operating_theater", out.assignedTheater);
        } else {
          pj.put("admission_day", "none");
        }
        outPatients.put(pj);
      }
      return outPatients;
    }

    public JSONArray getOutputNurses() {
      JSONArray outNurses = new JSONArray();

      for (OutputNurse out : this.outputNurses) {
        JSONObject nj = new JSONObject();
        nj.put("id", out.id);

        JSONArray assignments = new JSONArray();

        for (int i = 0; i < out.workingShifts.size(); i++) {
          JSONObject assignment = new JSONObject();
          int shiftIndex = out.workingShifts.get(i);
          int day = shiftIndex / in.ShiftsPerDay();
          String shiftName = in.ShiftName(shiftIndex % in.ShiftsPerDay());

          JSONArray shiftRooms = new JSONArray();
          for (String room : out.workingRooms.get(i)) {
            shiftRooms.put(room);
          }

          assignment.put("day", day);
          assignment.put("shift", shiftName);
          assignment.put("rooms", shiftRooms);

          assignments.put(assignment);
        }

        nj.put("assignments", assignments);
        outNurses.put(nj);
      }
      return outNurses;
    }

    public List<OutputPatient> swapPatients() {
      List<OutputPatient> out = new ArrayList<>();
      for (OutputPatient patient : this.tempOutputPatients) {
        OutputPatient p = new OutputPatient();
        p.id = patient.id;
        p.assignedDay = patient.assignedDay;
        p.assignedRoom = patient.assignedRoom;
        p.assignedTheater = patient.assignedTheater;
        out.add(p);
      }
      Random rnd = new Random();
      JSONObject baseOut = new JSONObject().put("patients", getOutputPatients(out)).put("nurses", getOutputNurses());
      int minViolations = new IHTP_Validator(this.in, baseOut, false).getTotalViolations();
      for (int i = 0; i < out.size(); i++) {
        int randomIndex;
        for (int j = 0; j < 10; j++) {
          do {
            randomIndex = rnd.nextInt(out.size());
          } while (randomIndex == i);
          // Swap patients
          String tempId = out.get(i).id;
          out.get(i).id = out.get(randomIndex).id;
          out.get(randomIndex).id = tempId;

          // Prepare output JSON
          JSONObject j_out = new JSONObject();
          JSONArray outPatients = getOutputPatients(out);
          JSONArray outNurses = getOutputNurses();

          j_out.put("patients", outPatients);
          j_out.put("nurses", outNurses);
          IHTP_Validator validator = new IHTP_Validator(this.in, j_out, false);
          int totalViolations = validator.getTotalViolations();

          if (totalViolations >= minViolations) {
            String tempId2 = out.get(i).id;
            out.get(i).id = out.get(randomIndex).id;
            out.get(randomIndex).id = tempId2;

          } else {
            minViolations = totalViolations;
            break;
          }

        }
      }
      return out;
    }

    private boolean placePatients(PriorityQueue<IHTP_Input.Patient> list, boolean mustPlace) {
      temporaryAfterPlace.clear();
      int previous = Integer.MAX_VALUE;
      // Mencoba memasukkan pasien (increased iterations)
      for (int i = 0; i < 5000; i++) {
        PriorityQueue<IHTP_Input.Patient> listCopy = new PriorityQueue<>(list);
        List<IHTP_Input.Patient> unscheduledPatients = new ArrayList<>();
        while (!listCopy.isEmpty()) {
          IHTP_Input.Patient p = listCopy.poll();
          OutputPatient out = new OutputPatient();
          boolean assigned = attemptRandomAssignment(p, MAX_ITERATIONS);
          if (!assigned) {
            unscheduledPatients.add(p);
          }
        }

        // Buat map pasien yang berhasil
        Map<String, OutputPatient> scheduledMap = new HashMap<>();
        for (OutputPatient op : temporaryAfterPlace) {
          scheduledMap.put(op.id, op);
        }

        if (mustPlace) {
          int totalUnschedulePatients = unscheduledPatients.size();

          if (totalUnschedulePatients < previous) {
            previous = totalUnschedulePatients;
            tempOutputPatients.clear();
            tempOutputPatients.addAll(temporaryAfterPlace);
            unscheduledPatientsInitial.clear();
            unscheduledPatientsInitial.addAll(unscheduledPatients);
          }

        } else {
          for (OutputPatient out : temporaryAfterPlace) {
            tempOutputPatients.add(out);
          }
        }

        if (unscheduledPatients.isEmpty())
          return true;
        else
          undoAll();

      }

      // Debug patient yang tidak terjadwalkan
      for (IHTP_Input.Patient p : unscheduledPatientsInitial) {
        System.out.println("Unscheduled patient ID: " + p.id);
      }
      // Sekarang unscheduledPatients berisi OutputPatient dari yang gagal dijadwalkan
      // Kamu bisa pakai untuk logging, analisis, dsb.
      // if (!unscheduledPatients.isEmpty()) {
      // System.out.println("Unscheduled patients (" + unscheduledPatients.size() +
      // "):");
      // for (OutputPatient out : unscheduledPatients) {
      // System.out.println(" ID: " + out.id);
      // }
      // }
      // === End tracking ===

      return false;
    }

    /* undoAll – resets occupancy maps so we can retry */
    private void undoAll() {
      for (int[] arr : roomOccupancy.values())
        Arrays.fill(arr, 0);
      for (IHTP_Input.Gender[] g : roomGender.values())
        Arrays.fill(g, null);
      for (int[] arr : surgeonUsage.values())
        Arrays.fill(arr, 0);
      for (int[] arr : theaterUsage.values())
        Arrays.fill(arr, 0);
      for (List<IHTP_Input.Patient>[] arr : patientsInRoomByDay.values())
        for (List<IHTP_Input.Patient> lst : arr)
          lst.clear();
      patientStartDay.clear();
      temporaryAfterPlace.clear();
    }

    private boolean attemptRandomAssignment(IHTP_Input.Patient p, int maxIter) {
      Random rnd = new Random();
      // day window
      int start = p.surgeryReleaseDay;
      int end = p.mandatory ? p.surgeryDueDay : totalDays - 1;
      end = Math.min(end, totalDays - 1);

      // room + OT candidate lists (compatible only)
      List<IHTP_Input.Room> rooms = new ArrayList<>();
      for (int i = 0; i < in.Rooms(); i++)
        if (!p.incompatibleRooms[i])
          rooms.add(in.roomsList.get(i));
      if (rooms.isEmpty())
        return false;
      List<String> theaters = new ArrayList<>();
      for (IHTP_Input.OperatingTheater ot : in.theatersList)
        theaters.add(ot.id);

      for (int iter = 0; iter < maxIter; iter++) {
        int day = start + rnd.nextInt(end - start + 1);
        IHTP_Input.Room room = rooms.get(rnd.nextInt(rooms.size()));
        String otId = theaters.get(rnd.nextInt(theaters.size()));

        // combined feasibility -------------------------------------------
        boolean feasible = true;
        if (dateWindowInvalid(p, day) || surgeonOvertime(p, day) || otOvertime(otId, day, p))
          feasible = false;
        else {
          for (int d = day; feasible && d < day + p.lengthOfStay && d < totalDays; d++)
            if (roomFull(room, d) || genderMix(room, d, p))
              feasible = false;
        }
        if (!feasible)
          continue; // try another triple

        // commit -----------------------------------------------------------
        OutputPatient out = new OutputPatient();
        out.id = p.id;
        out.assignedDay = day;
        out.assignedRoom = room.id;
        out.assignedTheater = otId;
        temporaryAfterPlace.add(out);
        patientStartDay.put(p, day);
        for (int d = day; d < day + p.lengthOfStay && d < totalDays; d++) {
          roomOccupancy.get(room.id)[d]++;
          if (roomGender.get(room.id)[d] == null)
            roomGender.get(room.id)[d] = p.gender;
          patientsInRoomByDay.get(room.id)[d].add(p);
        }
        surgeonUsage.get(in.surgeonsList.get(p.surgeon).id)[day] += p.surgeryDuration;
        theaterUsage.get(otId)[day] += p.surgeryDuration;
        return true;
      }
      return false; // give up after maxIter
    }

    public void attemptRandomNurseAssignment(int maxIterations) {
      Random rand = new Random();
      // Build list of nurses
      for (int n = 0; n < maxIterations; n++) {
        for (IHTP_Input.Room r : this.in.roomsList) {
          for (int i = 0; i < this.in.Shifts(); i++) {
            IHTP_Input.Nurse nurse = this.in.nursesList.get(rand.nextInt(this.in.nursesCount - 1));
            // Check nurses is in working shift
            if (!nurse.isWorkingInShift[i]) {
              continue;
            }
            nurseShiftAssignment.get(r.id)[i] = nurse.id;
          }
        }
      }
    }

    public void transformNurseSolution() {
      for (IHTP_Input.Nurse n : this.in.nursesList) {
        OutputNurse outNurse = new OutputNurse();
        outNurse.id = n.id;
        for (int i = 0; i < this.in.Shifts(); i++) {
          List<String> rooms = Arrays.asList(getRoomByShift(n.id, i));
          if (rooms != null && n.workingShifts.contains(i)) {
            outNurse.workingShifts.add(i);
            outNurse.workingRooms.add(rooms);
          }
        }
        outputNurses.add(outNurse);
      }
    }

    public String[] getRoomByShift(String nurseId, int shift) {
      List<String> room = new ArrayList<>();
      for (IHTP_Input.Room r : this.in.roomsList) {
        if (nurseShiftAssignment.get(r.id)[shift] == nurseId) {
          room.add(r.id);
        }
      }
      return room.toArray(new String[room.size()]);
    }

    public List<OutputNurse> getOutputNursesSolution() {
      return this.outputNurses;
    }
  }

  static class ILS_Solution {
    private IHTP_Input input;
    private String inputFile;
    private List<OutputPatient> currentSolution;
    private List<OutputNurse> currentNurseSolution;
    private int currentViolations;
    private Random random;

    /**
     * Constructor for ILS_Solution.
     *
     * @param input                Parsed input data (IHTP_Input).
     * @param inputFile            Path to the input JSON file.
     * @param initialSolution      Initial list of OutputPatient from generator.
     * @param initialNurseSolution Initial list of OutputNurse from generator.
     * @param initialViolations    Number of violations in the initial solution.
     */
    public ILS_Solution(IHTP_Input input, List<OutputPatient> initialSolution, List<OutputNurse> initialNurseSolution,
        int initialViolations) {
      this.input = input;
      this.currentSolution = new ArrayList<>(initialSolution);
      this.currentNurseSolution = new ArrayList<>(initialNurseSolution);
      this.currentViolations = initialViolations;
      this.random = new Random();
    }

    /** Getter for the current patient solution. */
    public List<OutputPatient> getCurrentSolution() {
      return currentSolution;
    }

    /** Getter for the current nurse solution. */
    public List<OutputNurse> getCurrentNurseSolution() {
      return currentNurseSolution;
    }

    /** Getter for the current violation count. */
    public int getCurrentViolations() {
      return currentViolations;
    }

    /**
     * Updates the current solution and violation count.
     *
     * @param solution   New list of OutputPatient.
     * @param violations New violation count.
     */
    public void setCurrentSolution(List<OutputPatient> solution, int violations) {
      this.currentSolution = new ArrayList<>(solution);
      this.currentViolations = violations;
    }

    public List<OutputPatient> shuffleSmall(List<OutputPatient> sol, int waves, double pNonRandom) {
      List<OutputPatient> cur = deepCopy(sol);
      for (int w = 0; w < waves; w++) {
        int k = Math.max(1, (int) Math.round(0.02 * cur.size())); // ~2% pasien
        for (int i = 0; i < k; i++) {
          if (random.nextDouble() < pNonRandom)
            guidedFixMajorViolation(cur);
          else {
            // random ringan agar tidak meledakkan pelanggaran
            if (random.nextBoolean())
              changePatientRoom(cur);
            else
              movePatientToNewDay(cur);
          }
        }
      }
      return cur;
    }

    private void guidedFixMajorViolation(List<OutputPatient> neighbor) {
      // nilai awal
      int baseViol = evaluateSolution(neighbor, this.currentNurseSolution);

      int trialsPickPatient = 6; // coba pilih beberapa pasien agar ada peluang kena "penyebab" pelanggaran
      int trialsMove = 6; // jumlah kandidat untuk 1 pasien

      int bestViol = baseViol;
      int bestIndex = -1;
      OutputPatient bestCandidate = null;

      for (int t = 0; t < trialsPickPatient; t++) {
        int idx = random.nextInt(neighbor.size());
        OutputPatient p = neighbor.get(idx);

        // skip kalau unscheduled & domain kosong
        List<String> roomIds = new ArrayList<>();
        for (int r = 0; r < input.Rooms(); r++)
          roomIds.add(input.RoomId(r));

        for (int k = 0; k < trialsMove; k++) {
          // simpan lama
          Integer oldDay = p.assignedDay;
          String oldRoom = p.assignedRoom;
          String oldTheater = p.assignedTheater;

          // proposal: geser salah satu komponen
          int moveType = random.nextInt(3);
          if (moveType == 0 || p.assignedDay == null) {
            p.assignedDay = random.nextInt(input.Days());
          } else if (moveType == 1) {
            p.assignedRoom = roomIds.get(random.nextInt(roomIds.size()));
          } else {
            p.assignedTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));
          }

          int v = evaluateSolution(neighbor, this.currentNurseSolution);

          // pilih kandidat terbaik (boleh sama atau lebih baik dari base)
          if (v < bestViol) {
            bestViol = v;
            bestIndex = idx;
            bestCandidate = new OutputPatient();
            bestCandidate.id = p.id;
            bestCandidate.assignedDay = p.assignedDay;
            bestCandidate.assignedRoom = p.assignedRoom;
            bestCandidate.assignedTheater = p.assignedTheater;
          }

          // rollback sebelum coba kandidat berikutnya
          p.assignedDay = oldDay;
          p.assignedRoom = oldRoom;
          p.assignedTheater = oldTheater;
        }
      }

      // commit kandidat terbaik kalau ada perbaikan
      if (bestIndex >= 0 && bestCandidate != null && bestViol <= baseViol) {
        OutputPatient target = neighbor.get(bestIndex);
        target.assignedDay = bestCandidate.assignedDay;
        target.assignedRoom = bestCandidate.assignedRoom;
        target.assignedTheater = bestCandidate.assignedTheater;
      }
    }

    public List<OutputPatient> getNeighborPAILS(List<OutputPatient> solution, double pNonRandom) {
      List<OutputPatient> neighbor = deepCopy(solution);
      if (random.nextDouble() < pNonRandom) {
        // move terarah: coba perbaiki pelanggaran besar
        guidedFixMajorViolation(neighbor);
      } else {
        // move acak ringan (pakai subset dari neighbor yang sudah ada)
        int method = random.nextInt(3);
        switch (method) {
        case 0:
          movePatientToNewDay(neighbor);
          break;
        case 1:
          changePatientRoom(neighbor);
          break;
        case 2:
          changePatientTheater(neighbor);
          break;
        }
      }
      return neighbor;
    }

    /**
     * Evaluates a candidate solution by writing it to a temporary JSON and invoking
     * IHTP_Validator to count violations.
     *
     * @param solution      List of OutputPatient to evaluate.
     * @param nurseSolution List of OutputNurse to evaluate.
     * @return Total number of violations for the given solution.
     */
    public int evaluateSolution(List<OutputPatient> solution, List<OutputNurse> nurseSolution) {
      JSONObject j_out = new JSONObject();
      JSONArray outPatients = getOutputPatientsJSON(solution);
      JSONArray outNurses = getOutputNursesJSON(nurseSolution);

      j_out.put("patients", outPatients);
      j_out.put("nurses", outNurses);
      IHTP_Validator validator = new IHTP_Validator(this.input, j_out, false);
      int violations = validator.getTotalViolations();

      // If near-optimal, we can log detailed violations for debugging
      // if (violations <= 5) {
      // System.out.println("Low violations detected: " + violations);
      // System.out.println(" Room Gender Mix: " + validator.getVRoomGender());
      // System.out.println(" Room Capacity: " + validator.getVRoomCap());
      // System.out.println(" Surgeon Overtime: " + validator.getVSurgeonOver());
      // System.out.println(" OT Overtime: " + validator.getVOTOver());
      // System.out.println(" Mandatory Unscheduled: " +
      // validator.getVMandatoryUnsch());
      // System.out.println(" Admission Day: " + validator.getVAdmissionDay());
      // System.out.println(" Nurse Presence: " + validator.getVNursePres());
      // System.out.println(" Uncovered Room: " + validator.getVUncovered());
      // }

      if (violations == 0) {
        System.out.println("VALIDATOR RETURNED ZERO VIOLATIONS - OPTIMAL SOLUTION FOUND");
      }

      return violations;
    }

    /**
     * Converts a list of OutputPatient into a JSONArray suitable for validation.
     */
    private JSONArray getOutputPatientsJSON(List<OutputPatient> output) {
      JSONArray outPatients = new JSONArray();
      for (OutputPatient out : output) {
        JSONObject pj = new JSONObject();
        pj.put("id", out.id);
        if (out.assignedDay != null) {
          pj.put("admission_day", out.assignedDay);
          pj.put("room", out.assignedRoom);
          pj.put("operating_theater", out.assignedTheater);
        } else {
          pj.put("admission_day", "none");
        }
        outPatients.put(pj);
      }
      return outPatients;
    }

    /**
     * Converts a list of OutputNurse into a JSONArray suitable for validation.
     */
    private JSONArray getOutputNursesJSON(List<OutputNurse> nurses) {
      JSONArray outNurses = new JSONArray();
      for (OutputNurse out : nurses) {
        JSONObject nj = new JSONObject();
        nj.put("id", out.id);

        JSONArray assignments = new JSONArray();
        for (int i = 0; i < out.workingShifts.size(); i++) {
          JSONObject assignment = new JSONObject();
          int shiftIndex = out.workingShifts.get(i);
          int day = shiftIndex / input.ShiftsPerDay();
          String shiftName = input.ShiftName(shiftIndex % input.ShiftsPerDay());

          JSONArray shiftRooms = new JSONArray();
          for (String room : out.workingRooms.get(i)) {
            shiftRooms.put(room);
          }

          assignment.put("day", day);
          assignment.put("shift", shiftName);
          assignment.put("rooms", shiftRooms);
          assignments.put(assignment);
        }

        nj.put("assignments", assignments);
        outNurses.put(nj);
      }
      return outNurses;
    }

    /**
     * Performs perturbation on a given solution. It randomly chooses one of three
     * perturbation methods: reassigning a subset of patients, swapping admission
     * days, or shuffling room assignments.
     *
     * @param solution Current list of OutputPatient to perturb.
     * @return A new perturbed list of OutputPatient.
     */
    public List<OutputPatient> perturb(List<OutputPatient> solution) {
      List<OutputPatient> perturbedSolution = deepCopy(solution);
      int method = random.nextInt(7);
      switch (method) {
      case 0:
        randomReassignPatients(perturbedSolution);
        break;
      case 1:
        swapPatientDays(perturbedSolution);
        break;
      case 2:
        shuffleRoomAssignments(perturbedSolution);
        break;
      case 3:
        shuffleRoomAssignments(perturbedSolution);
        swapPatientDays(perturbedSolution);
        break;
      case 4:
        randomReassignPatients(perturbedSolution);
        shuffleRoomAssignments(perturbedSolution);
        break;
      case 5:
        randomReassignPatients(perturbedSolution);
        swapPatientDays(perturbedSolution);
        break;
      case 6:
        swapRoomAndSchedulePatient(perturbedSolution);
        break;
      }
      return perturbedSolution;
    }

    /** Deep-copies a list of OutputPatient. */
    private List<OutputPatient> deepCopy(List<OutputPatient> solution) {
      List<OutputPatient> copy = new ArrayList<>();
      for (OutputPatient p : solution) {
        OutputPatient newP = new OutputPatient();
        newP.id = p.id;
        newP.assignedDay = p.assignedDay;
        newP.assignedRoom = p.assignedRoom;
        newP.assignedTheater = p.assignedTheater;
        copy.add(newP);
      }
      return copy;
    }

    private void swapRoomAndSchedulePatient(List<OutputPatient> solution) {
      int count = solution.size();
      int swapCount = random.nextInt(count / 4 + 1) + 5; // 5–25% of patients
      for (int i = 0; i < swapCount; i++) {
        int index1 = random.nextInt(solution.size());
        int index2 = random.nextInt(solution.size());
        if (index1 != index2) {
          OutputPatient p1 = solution.get(index1);
          OutputPatient p2 = solution.get(index2);
          if (p1.assignedDay != null && p2.assignedDay != null) {
            Integer tempDay = p1.assignedDay;
            p1.assignedDay = p2.assignedDay;
            p2.assignedDay = tempDay;

            String tempRoom = p1.assignedRoom;
            p1.assignedRoom = p2.assignedRoom;
            p2.assignedRoom = tempRoom;

            String tempTheater = p1.assignedTheater;
            p1.assignedTheater = p2.assignedTheater;
            p2.assignedTheater = tempTheater;
          }
        }
      }
    }

    /**
     * Randomly reassigns 10–30% of patients in the solution to new days, rooms, and
     * theaters, or marks a small subset as unscheduled.
     */
    private void randomReassignPatients(List<OutputPatient> solution) {
      int count = solution.size();
      int perturbCount = Math.max(1, (int) Math.round(0.02 * solution.size())); // ~2%

      for (int i = 0; i < perturbCount; i++) {
        int index = random.nextInt(solution.size());
        OutputPatient p = solution.get(index);

        if (p.assignedDay == null) {
          // Assign a random schedule if previously unscheduled
          p.assignedDay = random.nextInt(input.Days());
          int roomIndex = random.nextInt(input.Rooms());
          p.assignedRoom = input.RoomId(roomIndex);
          int theaterIndex = random.nextInt(input.OperatingTheaters());
          p.assignedTheater = input.OperatingTheaterId(theaterIndex);
        } else {
          // 5% chance to un-schedule
          if (random.nextDouble() < 0.05) {
            p.assignedDay = null;
            p.assignedRoom = null;
            p.assignedTheater = null;
          } else {
            // Reassign to a new random valid slot
            p.assignedDay = random.nextInt(input.Days());
            int roomIndex = random.nextInt(input.Rooms());
            p.assignedRoom = input.RoomId(roomIndex);
            int theaterIndex = random.nextInt(input.OperatingTheaters());
            p.assignedTheater = input.OperatingTheaterId(theaterIndex);
          }
        }
      }
    }

    /**
     * Swaps admission days between 5–25% of the currently scheduled patients.
     */
    private void swapPatientDays(List<OutputPatient> solution) {
      int swapCount = Math.max(1, (int) Math.round(0.02 * solution.size())); // ~2%

      for (int i = 0; i < swapCount; i++) {
        int index1 = random.nextInt(solution.size());
        int index2 = random.nextInt(solution.size());
        if (index1 != index2) {
          OutputPatient p1 = solution.get(index1);
          OutputPatient p2 = solution.get(index2);
          if (p1.assignedDay != null && p2.assignedDay != null) {
            Integer tempDay = p1.assignedDay;
            p1.assignedDay = p2.assignedDay;
            p2.assignedDay = tempDay;
          }
        }
      }
    }

    /**
     * Randomly shuffles room assignments for 10–35% of the currently scheduled
     * patients.
     */
    private void shuffleRoomAssignments(List<OutputPatient> solution) {
      List<String> roomIds = new ArrayList<>();
      for (int r = 0; r < input.Rooms(); r++) {
        roomIds.add(input.RoomId(r));
      }
      int reshuffleCount = Math.max(1, (int) Math.round(0.02 * solution.size())); // ~2%

      for (int i = 0; i < reshuffleCount; i++) {
        int index = random.nextInt(solution.size());
        OutputPatient p = solution.get(index);
        if (p.assignedDay != null) {
          p.assignedRoom = roomIds.get(random.nextInt(roomIds.size()));
        }
      }
    }

    /**
     * FIX #1: Improved local search - lebih agresif untuk unscheduled patients
     */
    public List<OutputPatient> localSearch(List<OutputPatient> solution, List<OutputNurse> nurseSolution) {
      List<OutputPatient> currentSolCopy = deepCopy(solution);
      List<OutputPatient> bestSolution = deepCopy(solution);

      int currentViol = evaluateSolution(currentSolCopy, nurseSolution);
      int bestViol = currentViol;

      int iterations = 3500;
      int noImprovementCount = 0;
      final int MAX_NO_IMPROVEMENT = 200;

      // FIX: Hitung unscheduled mandatory patients
      int unscheduledCount = countUnscheduledMandatory(currentSolCopy);

      for (int i = 0; i < iterations && bestViol > 0; i++) {
        List<OutputPatient> neighborSolution;

        // FIX: Jika ada unscheduled mandatory, gunakan move lebih agresif
        if (unscheduledCount > 0 && random.nextDouble() < 0.85) {
          neighborSolution = getNeighborForUnscheduled(currentSolCopy);
        } else {
          neighborSolution = getNeighbor(currentSolCopy);
        }

        int neighborViol = evaluateSolution(neighborSolution, nurseSolution);

        if (neighborViol < currentViol) {
          currentSolCopy = deepCopy(neighborSolution);
          currentViol = neighborViol;
          noImprovementCount = 0;
          unscheduledCount = countUnscheduledMandatory(currentSolCopy);

          if (neighborViol < bestViol) {
            bestSolution = deepCopy(neighborSolution);
            bestViol = neighborViol;
          }
        } else {
          noImprovementCount++;
        }

        if (noImprovementCount > MAX_NO_IMPROVEMENT) {
          break;
        }
      }

      return bestSolution;
    }

    /**
     * FIX #2: Count unscheduled MANDATORY patients only
     */
    private int countUnscheduledMandatory(List<OutputPatient> solution) {
      int count = 0;
      for (OutputPatient p : solution) {
        if (p.assignedDay == null && input.patientIndexById.containsKey(p.id)) {
          IHTP_Input.Patient patient = input.patientsList.get(input.patientIndexById.get(p.id));
          if (patient.mandatory) {
            count++;
          }
        }
      }
      return count;
    }

    /**
     * FIX #3: Neighbor moves khusus untuk unscheduled patients - 9 options now
     */
    private List<OutputPatient> getNeighborForUnscheduled(List<OutputPatient> solution) {
      List<OutputPatient> neighbor = deepCopy(solution);

      int moveType = random.nextInt(9); // 9 opsi sekarang
      switch (moveType) {
      case 0:
        smartInsertUnscheduled(neighbor);
        break;
      case 1:
        chainKickAndInsert(neighbor);
        break;
      case 2:
        forceInsertAndKickMultiple(neighbor);
        break;
      case 3:
        swapWithScheduledPatient(neighbor);
        break;
      case 4:
        multiTargetInsert(neighbor);
        break;
      case 5:
        exhaustiveInsertUnscheduled(neighbor);
        break;
      case 6:
        doubleKickAndInsert(neighbor);
        break;
      case 7:
        prioritizedTightWindowInsert(neighbor);
        break;
      case 8:
        aggressiveMultiKick(neighbor);
        break;
      }
      return neighbor;
    }

    /**
     * FIX #4: Smart insert - cari slot valid untuk unscheduled patient
     */
    private void smartInsertUnscheduled(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      OutputPatient target = unscheduled.get(random.nextInt(unscheduled.size()));
      IHTP_Input.Patient patientData = input.patientsList.get(input.patientIndexById.get(target.id));

      int bestViol = Integer.MAX_VALUE;
      Integer bestDay = null;
      String bestRoom = null;
      String bestTheater = null;

      int startDay = patientData.surgeryReleaseDay;
      int dueDay = Math.min(patientData.surgeryDueDay, input.Days() - 1);

      // Coba 50 kombinasi random dalam valid window
      for (int trial = 0; trial < 400; trial++) {
        if (dueDay < startDay)
          continue;

        int day = startDay + random.nextInt(dueDay - startDay + 1);
        String room = input.RoomId(random.nextInt(input.Rooms()));
        String theater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));

        target.assignedDay = day;
        target.assignedRoom = room;
        target.assignedTheater = theater;

        int viol = evaluateSolution(solution, this.currentNurseSolution);

        if (viol < bestViol) {
          bestViol = viol;
          bestDay = day;
          bestRoom = room;
          bestTheater = theater;
        }
      }

      if (bestDay != null) {
        target.assignedDay = bestDay;
        target.assignedRoom = bestRoom;
        target.assignedTheater = bestTheater;
      } else {
        target.assignedDay = null;
        target.assignedRoom = null;
        target.assignedTheater = null;
      }
    }

    /**
     * FIX #5: Chain kick - tendang MULTIPLE patients untuk buat ruang
     */
    private void chainKickAndInsert(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      OutputPatient target = unscheduled.get(random.nextInt(unscheduled.size()));
      IHTP_Input.Patient targetData = input.patientsList.get(input.patientIndexById.get(target.id));

      int startDay = targetData.surgeryReleaseDay;
      int dueDay = Math.min(targetData.surgeryDueDay, input.Days() - 1);
      if (dueDay < startDay)
        return;

      int targetDay = startDay + random.nextInt(dueDay - startDay + 1);
      String targetRoom = input.RoomId(random.nextInt(input.Rooms()));
      String targetTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));

      // Find ALL conflicting patients
      List<OutputPatient> toKick = findAllConflicts(solution, target, targetData, targetDay, targetRoom);

      // Sort: prioritas kick optional patients dulu
      toKick.sort((a, b) -> {
        IHTP_Input.Patient pa = input.patientsList.get(input.patientIndexById.get(a.id));
        IHTP_Input.Patient pb = input.patientsList.get(input.patientIndexById.get(b.id));
        if (!pa.mandatory && pb.mandatory)
          return -1;
        if (pa.mandatory && !pb.mandatory)
          return 1;
        return 0;
      });

      // Kick sampai 4 patients (lebih agresif dari sebelumnya yang cuma 1)
      int kickCount = Math.min(6, toKick.size());
      for (int i = 0; i < kickCount; i++) {
        OutputPatient victim = toKick.get(i);
        victim.assignedDay = null;
        victim.assignedRoom = null;
        victim.assignedTheater = null;
      }

      // Insert target
      target.assignedDay = targetDay;
      target.assignedRoom = targetRoom;
      target.assignedTheater = targetTheater;
    }

    /**
     * FIX #6: Force insert dengan multiple kicks
     */
    private void forceInsertAndKickMultiple(List<OutputPatient> solution) {
      List<OutputPatient> unscheduledList = new ArrayList<>();
      for (OutputPatient p : solution) {
        if (p.assignedDay == null) {
          unscheduledList.add(p);
        }
      }
      if (unscheduledList.isEmpty())
        return;

      OutputPatient p = unscheduledList.get(random.nextInt(unscheduledList.size()));

      int startDay = input.patientsList.get(input.patientIndexById.get(p.id)).surgeryReleaseDay;
      int dueDay = input.patientsList.get(input.patientIndexById.get(p.id)).surgeryDueDay;
      dueDay = Math.min(dueDay, input.Days() - 1);
      if (dueDay < startDay)
        dueDay = startDay;

      int randomDay = startDay + random.nextInt(dueDay - startDay + 1);
      String randomRoom = input.RoomId(random.nextInt(input.Rooms()));
      String randomTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));

      p.assignedDay = randomDay;
      p.assignedRoom = randomRoom;
      p.assignedTheater = randomTheater;

      int pDuration = input.patientsList.get(input.patientIndexById.get(p.id)).lengthOfStay;
      int pEndDay = Math.min(randomDay + pDuration - 1, input.Days() - 1);
      IHTP_Input.Gender pGender = input.patientsList.get(input.patientIndexById.get(p.id)).gender;
      int roomCapacity = input.roomsList.get(input.roomIndexById.get(randomRoom)).capacity;

      // Find ALL conflicts (gender + capacity)
      List<OutputPatient> conflicts = new ArrayList<>();
      for (OutputPatient other : solution) {
        if (other == p || other.assignedDay == null)
          continue;
        if (!other.assignedRoom.equals(randomRoom))
          continue;

        int otherStart = other.assignedDay;
        int otherDuration = input.patientsList.get(input.patientIndexById.get(other.id)).lengthOfStay;
        int otherEnd = Math.min(otherStart + otherDuration - 1, input.Days() - 1);

        if (randomDay <= otherEnd && otherStart <= pEndDay) {
          conflicts.add(other);
        }
      }

      // Kick ALL dengan gender berbeda
      for (OutputPatient existing : conflicts) {
        IHTP_Input.Gender existingGender = input.patientsList.get(input.patientIndexById.get(existing.id)).gender;
        if (existingGender != pGender) {
          existing.assignedDay = null;
          existing.assignedRoom = null;
          existing.assignedTheater = null;
        }
      }

      // Hitung remaining conflicts & kick jika masih over capacity
      int remaining = 1;
      List<OutputPatient> survivors = new ArrayList<>();
      for (OutputPatient c : conflicts) {
        if (c.assignedDay != null) {
          remaining++;
          survivors.add(c);
        }
      }

      // KICK sampai capacity terpenuhi (bukan cuma 1!)
      while (remaining > roomCapacity && !survivors.isEmpty()) {
        OutputPatient victim = survivors.get(random.nextInt(survivors.size()));
        victim.assignedDay = null;
        victim.assignedRoom = null;
        victim.assignedTheater = null;
        survivors.remove(victim);
        remaining--;
      }
    }

    /**
     * FIX #7: Swap unscheduled dengan scheduled patient
     */
    private void swapWithScheduledPatient(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      List<OutputPatient> scheduled = new ArrayList<>();

      for (OutputPatient p : solution) {
        if (p.assignedDay != null) {
          scheduled.add(p);
        }
      }

      if (unscheduled.isEmpty() || scheduled.isEmpty())
        return;

      OutputPatient unsch = unscheduled.get(random.nextInt(unscheduled.size()));
      IHTP_Input.Patient unschData = input.patientsList.get(input.patientIndexById.get(unsch.id));

      int baseViol = evaluateSolution(solution, this.currentNurseSolution);
      int bestViol = baseViol;
      OutputPatient bestSwapPartner = null;

      for (int trial = 0; trial < Math.min(60, scheduled.size()); trial++) {
        OutputPatient sch = scheduled.get(random.nextInt(scheduled.size()));

        int schDay = sch.assignedDay;
        // Skip jika di luar valid window
        if (schDay < unschData.surgeryReleaseDay || schDay > unschData.surgeryDueDay) {
          continue;
        }

        // Perform swap
        Integer savedDay = sch.assignedDay;
        String savedRoom = sch.assignedRoom;
        String savedTheater = sch.assignedTheater;

        unsch.assignedDay = savedDay;
        unsch.assignedRoom = savedRoom;
        unsch.assignedTheater = savedTheater;

        sch.assignedDay = null;
        sch.assignedRoom = null;
        sch.assignedTheater = null;

        int viol = evaluateSolution(solution, this.currentNurseSolution);

        if (viol < bestViol) {
          bestViol = viol;
          bestSwapPartner = sch;
        }

        // Restore
        sch.assignedDay = savedDay;
        sch.assignedRoom = savedRoom;
        sch.assignedTheater = savedTheater;
        unsch.assignedDay = null;
        unsch.assignedRoom = null;
        unsch.assignedTheater = null;
      }

      // Apply best swap
      if (bestSwapPartner != null) {
        unsch.assignedDay = bestSwapPartner.assignedDay;
        unsch.assignedRoom = bestSwapPartner.assignedRoom;
        unsch.assignedTheater = bestSwapPartner.assignedTheater;

        bestSwapPartner.assignedDay = null;
        bestSwapPartner.assignedRoom = null;
        bestSwapPartner.assignedTheater = null;
      }
    }

    /**
     * FIX #8: Multi-target insert - coba insert semua unscheduled sekaligus
     */
    private void multiTargetInsert(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      int baseViol = evaluateSolution(solution, this.currentNurseSolution);

      for (OutputPatient target : unscheduled) {
        IHTP_Input.Patient targetData = input.patientsList.get(input.patientIndexById.get(target.id));

        int startDay = targetData.surgeryReleaseDay;
        int dueDay = Math.min(targetData.surgeryDueDay, input.Days() - 1);
        if (dueDay < startDay)
          continue;

        int bestViol = Integer.MAX_VALUE;
        Integer bestDay = null;
        String bestRoom = null;
        String bestTheater = null;
        List<OutputPatient> bestKicked = null;

        for (int trial = 0; trial < 60; trial++) {
          int tryDay = startDay + random.nextInt(dueDay - startDay + 1);
          String tryRoom = input.RoomId(random.nextInt(input.Rooms()));
          String tryTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));

          target.assignedDay = tryDay;
          target.assignedRoom = tryRoom;
          target.assignedTheater = tryTheater;

          // Temporarily kick conflicts
          List<OutputPatient> conflicts = findAllConflicts(solution, target, targetData, tryDay, tryRoom);
          List<Integer> savedDays = new ArrayList<>();
          List<String> savedRooms = new ArrayList<>();
          List<String> savedTheaters = new ArrayList<>();

          for (OutputPatient c : conflicts) {
            savedDays.add(c.assignedDay);
            savedRooms.add(c.assignedRoom);
            savedTheaters.add(c.assignedTheater);
            c.assignedDay = null;
            c.assignedRoom = null;
            c.assignedTheater = null;
          }

          int viol = evaluateSolution(solution, this.currentNurseSolution);

          if (viol < bestViol) {
            bestViol = viol;
            bestDay = tryDay;
            bestRoom = tryRoom;
            bestTheater = tryTheater;
            bestKicked = new ArrayList<>(conflicts);
          }

          // Restore
          for (int i = 0; i < conflicts.size(); i++) {
            conflicts.get(i).assignedDay = savedDays.get(i);
            conflicts.get(i).assignedRoom = savedRooms.get(i);
            conflicts.get(i).assignedTheater = savedTheaters.get(i);
          }
        }

        if (bestDay != null && bestViol < baseViol) {
          target.assignedDay = bestDay;
          target.assignedRoom = bestRoom;
          target.assignedTheater = bestTheater;

          if (bestKicked != null) {
            for (OutputPatient c : bestKicked) {
              c.assignedDay = null;
              c.assignedRoom = null;
              c.assignedTheater = null;
            }
          }
          baseViol = bestViol;
        } else {
          target.assignedDay = null;
          target.assignedRoom = null;
          target.assignedTheater = null;
        }
      }
    }

    /**
     * FIX #9: Exhaustive insert - coba SEMUA kombinasi valid untuk 1 pasien
     */
    private void exhaustiveInsertUnscheduled(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      OutputPatient target = unscheduled.get(random.nextInt(unscheduled.size()));
      IHTP_Input.Patient patientData = input.patientsList.get(input.patientIndexById.get(target.id));

      int bestViol = Integer.MAX_VALUE;
      Integer bestDay = null;
      String bestRoom = null;
      String bestTheater = null;

      int startDay = patientData.surgeryReleaseDay;
      int dueDay = Math.min(patientData.surgeryDueDay, input.Days() - 1);
      if (dueDay < startDay)
        return;

      // Coba SEMUA hari dalam window
      for (int day = startDay; day <= dueDay; day++) {
        // Coba SEMUA room
        for (int r = 0; r < input.Rooms(); r++) {
          String room = input.RoomId(r);
          // Coba SEMUA theater
          for (int t = 0; t < input.OperatingTheaters(); t++) {
            String theater = input.OperatingTheaterId(t);

            target.assignedDay = day;
            target.assignedRoom = room;
            target.assignedTheater = theater;

            int viol = evaluateSolution(solution, this.currentNurseSolution);

            if (viol < bestViol) {
              bestViol = viol;
              bestDay = day;
              bestRoom = room;
              bestTheater = theater;
            }
          }
        }
      }

      if (bestDay != null) {
        target.assignedDay = bestDay;
        target.assignedRoom = bestRoom;
        target.assignedTheater = bestTheater;
      } else {
        target.assignedDay = null;
        target.assignedRoom = null;
        target.assignedTheater = null;
      }
    }

    /**
     * FIX #10: Double kick - tendang 2 pasien di 2 room berbeda, insert 1
     * unscheduled
     */
    private void doubleKickAndInsert(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      List<OutputPatient> scheduled = new ArrayList<>();
      for (OutputPatient p : solution) {
        if (p.assignedDay != null)
          scheduled.add(p);
      }
      if (scheduled.size() < 2)
        return;

      OutputPatient target = unscheduled.get(random.nextInt(unscheduled.size()));
      IHTP_Input.Patient targetData = input.patientsList.get(input.patientIndexById.get(target.id));

      int baseViol = evaluateSolution(solution, this.currentNurseSolution);
      int bestViol = baseViol;

      Integer bestDay = null;
      String bestRoom = null;
      String bestTheater = null;
      OutputPatient bestVictim1 = null;
      OutputPatient bestVictim2 = null;

      int startDay = targetData.surgeryReleaseDay;
      int dueDay = Math.min(targetData.surgeryDueDay, input.Days() - 1);
      if (dueDay < startDay)
        return;

      for (int trial = 0; trial < 40; trial++) {
        // Pilih 2 victim random
        OutputPatient victim1 = scheduled.get(random.nextInt(scheduled.size()));
        OutputPatient victim2;
        do {
          victim2 = scheduled.get(random.nextInt(scheduled.size()));
        } while (victim2 == victim1);

        // Simpan state
        Integer v1Day = victim1.assignedDay, v2Day = victim2.assignedDay;
        String v1Room = victim1.assignedRoom, v2Room = victim2.assignedRoom;
        String v1Theater = victim1.assignedTheater, v2Theater = victim2.assignedTheater;

        // Kick both
        victim1.assignedDay = null;
        victim1.assignedRoom = null;
        victim1.assignedTheater = null;
        victim2.assignedDay = null;
        victim2.assignedRoom = null;
        victim2.assignedTheater = null;

        // Insert target ke slot victim1
        if (v1Day >= startDay && v1Day <= dueDay) {
          target.assignedDay = v1Day;
          target.assignedRoom = v1Room;
          target.assignedTheater = v1Theater;

          int viol = evaluateSolution(solution, this.currentNurseSolution);
          if (viol < bestViol) {
            bestViol = viol;
            bestDay = v1Day;
            bestRoom = v1Room;
            bestTheater = v1Theater;
            bestVictim1 = victim1;
            bestVictim2 = victim2;
          }
        }

        // Restore
        target.assignedDay = null;
        target.assignedRoom = null;
        target.assignedTheater = null;
        victim1.assignedDay = v1Day;
        victim1.assignedRoom = v1Room;
        victim1.assignedTheater = v1Theater;
        victim2.assignedDay = v2Day;
        victim2.assignedRoom = v2Room;
        victim2.assignedTheater = v2Theater;
      }

      // Apply best
      if (bestDay != null && bestViol < baseViol) {
        bestVictim1.assignedDay = null;
        bestVictim1.assignedRoom = null;
        bestVictim1.assignedTheater = null;
        bestVictim2.assignedDay = null;
        bestVictim2.assignedRoom = null;
        bestVictim2.assignedTheater = null;
        target.assignedDay = bestDay;
        target.assignedRoom = bestRoom;
        target.assignedTheater = bestTheater;
      }
    }

    /**
     * Prioritized tight window insert - targets patients with smallest surgery
     * windows FIRST
     */
    private void prioritizedTightWindowInsert(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      // Sort by window size (smallest first - most constrained)
      unscheduled.sort((a, b) -> {
        IHTP_Input.Patient pa = input.patientsList.get(input.patientIndexById.get(a.id));
        IHTP_Input.Patient pb = input.patientsList.get(input.patientIndexById.get(b.id));
        int windowA = pa.surgeryDueDay - pa.surgeryReleaseDay;
        int windowB = pb.surgeryDueDay - pb.surgeryReleaseDay;
        return Integer.compare(windowA, windowB); // Smaller window = higher priority
      });

      // Pick the MOST constrained patient (smallest window)
      OutputPatient target = unscheduled.get(0);
      IHTP_Input.Patient targetData = input.patientsList.get(input.patientIndexById.get(target.id));

      int startDay = targetData.surgeryReleaseDay;
      int dueDay = Math.min(targetData.surgeryDueDay, input.Days() - 1);
      if (dueDay < startDay)
        return;

      int bestViol = Integer.MAX_VALUE;
      Integer bestDay = null;
      String bestRoom = null;
      String bestTheater = null;

      // Try EVERY valid day for this constrained patient
      for (int day = startDay; day <= dueDay; day++) {
        for (int r = 0; r < input.Rooms(); r++) {
          // Skip incompatible rooms
          if (targetData.incompatibleRooms != null && r < targetData.incompatibleRooms.length
              && targetData.incompatibleRooms[r])
            continue;

          String room = input.RoomId(r);
          for (int t = 0; t < input.OperatingTheaters(); t++) {
            String theater = input.OperatingTheaterId(t);

            target.assignedDay = day;
            target.assignedRoom = room;
            target.assignedTheater = theater;

            int viol = evaluateSolution(solution, this.currentNurseSolution);

            if (viol < bestViol) {
              bestViol = viol;
              bestDay = day;
              bestRoom = room;
              bestTheater = theater;
            }
          }
        }
      }

      if (bestDay != null) {
        target.assignedDay = bestDay;
        target.assignedRoom = bestRoom;
        target.assignedTheater = bestTheater;
      } else {
        target.assignedDay = null;
        target.assignedRoom = null;
        target.assignedTheater = null;
      }
    }

    /**
     * Aggressive multi-kick - kick up to 4 patients to make room for unscheduled
     * mandatory
     */
    private void aggressiveMultiKick(List<OutputPatient> solution) {
      List<OutputPatient> unscheduled = getUnscheduledMandatory(solution);
      if (unscheduled.isEmpty())
        return;

      List<OutputPatient> scheduled = new ArrayList<>();
      for (OutputPatient p : solution) {
        if (p.assignedDay != null)
          scheduled.add(p);
      }
      if (scheduled.size() < 4)
        return;

      // Sort unscheduled by window size (smallest first)
      unscheduled.sort((a, b) -> {
        IHTP_Input.Patient pa = input.patientsList.get(input.patientIndexById.get(a.id));
        IHTP_Input.Patient pb = input.patientsList.get(input.patientIndexById.get(b.id));
        int windowA = pa.surgeryDueDay - pa.surgeryReleaseDay;
        int windowB = pb.surgeryDueDay - pb.surgeryReleaseDay;
        return Integer.compare(windowA, windowB);
      });

      OutputPatient target = unscheduled.get(0);
      IHTP_Input.Patient targetData = input.patientsList.get(input.patientIndexById.get(target.id));

      int startDay = targetData.surgeryReleaseDay;
      int dueDay = Math.min(targetData.surgeryDueDay, input.Days() - 1);
      if (dueDay < startDay)
        return;

      int baseViol = evaluateSolution(solution, this.currentNurseSolution);
      int bestViol = baseViol;

      Integer bestDay = null;
      String bestRoom = null;
      String bestTheater = null;
      List<OutputPatient> bestVictims = null;

      for (int trial = 0; trial < 80; trial++) {
        // Pick 2-4 random victims
        int numVictims = 2 + random.nextInt(3); // 2, 3, or 4
        List<OutputPatient> victims = new ArrayList<>();
        while (victims.size() < numVictims && victims.size() < scheduled.size()) {
          OutputPatient v = scheduled.get(random.nextInt(scheduled.size()));
          if (!victims.contains(v))
            victims.add(v);
        }

        // Save their states
        List<Integer> savedDays = new ArrayList<>();
        List<String> savedRooms = new ArrayList<>();
        List<String> savedTheaters = new ArrayList<>();
        for (OutputPatient v : victims) {
          savedDays.add(v.assignedDay);
          savedRooms.add(v.assignedRoom);
          savedTheaters.add(v.assignedTheater);
          v.assignedDay = null;
          v.assignedRoom = null;
          v.assignedTheater = null;
        }

        // Try to insert target in victim0's slot if valid
        if (savedDays.get(0) >= startDay && savedDays.get(0) <= dueDay) {
          target.assignedDay = savedDays.get(0);
          target.assignedRoom = savedRooms.get(0);
          target.assignedTheater = savedTheaters.get(0);

          int viol = evaluateSolution(solution, this.currentNurseSolution);
          if (viol < bestViol) {
            bestViol = viol;
            bestDay = savedDays.get(0);
            bestRoom = savedRooms.get(0);
            bestTheater = savedTheaters.get(0);
            bestVictims = new ArrayList<>(victims);
          }
        }

        // Restore
        target.assignedDay = null;
        target.assignedRoom = null;
        target.assignedTheater = null;
        for (int i = 0; i < victims.size(); i++) {
          victims.get(i).assignedDay = savedDays.get(i);
          victims.get(i).assignedRoom = savedRooms.get(i);
          victims.get(i).assignedTheater = savedTheaters.get(i);
        }
      }

      // Apply best
      if (bestDay != null && bestViol < baseViol && bestVictims != null) {
        for (OutputPatient v : bestVictims) {
          v.assignedDay = null;
          v.assignedRoom = null;
          v.assignedTheater = null;
        }
        target.assignedDay = bestDay;
        target.assignedRoom = bestRoom;
        target.assignedTheater = bestTheater;
      }
    }

    // Helper methods
    private List<OutputPatient> getUnscheduledMandatory(List<OutputPatient> solution) {
      List<OutputPatient> result = new ArrayList<>();
      for (OutputPatient p : solution) {
        if (p.assignedDay == null && input.patientIndexById.containsKey(p.id)) {
          IHTP_Input.Patient patient = input.patientsList.get(input.patientIndexById.get(p.id));
          if (patient.mandatory) {
            result.add(p);
          }
        }
      }
      return result;
    }

    private List<OutputPatient> findAllConflicts(List<OutputPatient> solution, OutputPatient target,
        IHTP_Input.Patient targetData, int targetDay, String targetRoom) {
      List<OutputPatient> conflicts = new ArrayList<>();

      int pStart = targetDay;
      int pEnd = Math.min(pStart + targetData.lengthOfStay - 1, input.Days() - 1);

      for (OutputPatient other : solution) {
        if (other == target || other.assignedDay == null)
          continue;
        if (!other.assignedRoom.equals(targetRoom))
          continue;

        IHTP_Input.Patient otherData = input.patientsList.get(input.patientIndexById.get(other.id));
        int oStart = other.assignedDay;
        int oEnd = Math.min(oStart + otherData.lengthOfStay - 1, input.Days() - 1);

        // Check overlap
        if (pStart <= oEnd && oStart <= pEnd) {
          conflicts.add(other);
        }
      }

      return conflicts;
    }

    /**
     * Generates a single neighbor by applying one of five move types with dynamic
     * probabilities. If there are unscheduled patients, case 4
     * (assignRandomToUnscheduled) gets higher probability.
     */
    /**
     * Generates neighbor with threshold-based probability adjustment.
     */
    private List<OutputPatient> getNeighbor(List<OutputPatient> solution) {
      List<OutputPatient> neighbor = deepCopy(solution);

      // 1. Hitung jumlah pasien yang belum terjadwal
      int unscheduledCount = 0;
      for (OutputPatient p : neighbor) { // Pakai 'neighbor' atau 'solution' sama saja disini
        if (p.assignedDay == null) {
          unscheduledCount++;
        }
      }

      int moveType;

      // 2. Logika Probabilitas (Updated untuk Case 5)
      if (unscheduledCount > 0) {
        double rand = random.nextDouble();
        double urgentProbability; // Probabilitas melakukan force insert (Kick)

        // Semakin banyak unscheduled, semakin agresif kita nge-kick pasien lain
        if (unscheduledCount >= 10) {
          urgentProbability = 0.85;
        } else if (unscheduledCount >= 5) {
          urgentProbability = 0.75;
        } else if (unscheduledCount >= 3) {
          urgentProbability = 0.65;
        } else if (unscheduledCount >= 1) {
          urgentProbability = 0.55;
        } else {
          urgentProbability = 0.0;
        }

        if (rand < urgentProbability) {
          moveType = 5; // <--- FORCE INSERT & KICK
        } else {
          // Sisanya dibagi rata ke move 0-4 (Standard moves + Random Assign)
          moveType = random.nextInt(5);
        }
      } else {
        // Jika semua sudah terjadwal, lakukan optimasi biasa (0-3)
        moveType = random.nextInt(4);
      }

      // 3. Eksekusi Move
      switch (moveType) {
      case 0:
        movePatientToNewDay(neighbor);
        break;
      case 1:
        changePatientRoom(neighbor);
        break;
      case 2:
        changePatientTheater(neighbor);
        break;
      case 3:
        swapTwoPatients(neighbor);
        break;
      case 4:
        assignRandomToUnscheduled(neighbor); // Assign sopan (cari yang kosong)
        break;
      case 5:
        forceInsertAndKick(neighbor); // <--- Assign MAKSA (tendang orang lain)
        break;
      }

      return neighbor;
    }

    /**
     * Memaksa pasien Unscheduled masuk ke slot acak, dan "menendang" (un-schedule)
     * pasien lain yang bentrok di kamar tersebut agar kapasitas muat.
     */
    private void forceInsertAndKick(List<OutputPatient> solution) {
      // 1. Cari semua pasien unscheduled
      List<OutputPatient> unscheduledList = new ArrayList<>();
      for (OutputPatient p : solution) {
        if (p.assignedDay == null) {
          unscheduledList.add(p);
        }
      }

      if (unscheduledList.isEmpty())
        return;

      // 2. Pilih 1 pasien random untuk dipaksa masuk
      OutputPatient p = unscheduledList.get(random.nextInt(unscheduledList.size()));

      // 3. Generate lokasi & waktu random (Valid Window Only)
      // Kita coba cari hari yang valid dulu agar tidak melanggar Surgeon/Patient
      // Availability
      int startDay = input.patientsList.get(input.patientIndexById.get(p.id)).surgeryReleaseDay;
      int dueDay = input.patientsList.get(input.patientIndexById.get(p.id)).surgeryDueDay;

      // Clamp dueDay agar tidak melebihi total hari
      dueDay = Math.min(dueDay, input.Days() - 1);

      if (dueDay < startDay)
        dueDay = startDay; // Safety check

      int randomDay = startDay + random.nextInt(dueDay - startDay + 1);
      String randomRoom = input.RoomId(random.nextInt(input.Rooms()));
      String randomTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));

      // 4. ASSIGN PAKSA (Force Insert)
      p.assignedDay = randomDay;
      p.assignedRoom = randomRoom;
      p.assignedTheater = randomTheater;

      // 5. KICK CONFLICTING PATIENTS (Ejection Chain)
      // Kita cari siapa saja yang ada di kamar itu pada hari itu
      List<OutputPatient> patientsInTargetRoom = new ArrayList<>();

      // Ambil info durasi rawat inap pasien P (yang baru masuk)
      int pDuration = input.patientsList.get(input.patientIndexById.get(p.id)).lengthOfStay;
      int pEndDay = Math.min(randomDay + pDuration - 1, input.Days() - 1);

      for (OutputPatient other : solution) {
        // Jangan cek diri sendiri atau pasien yang belum ada jadwal
        if (other == p || other.assignedDay == null)
          continue;

        // Cek apakah 'other' ada di room yang sama
        if (other.assignedRoom.equals(randomRoom)) {
          // Cek overlap hari
          int otherStart = other.assignedDay;
          int otherDuration = input.patientsList.get(input.patientIndexById.get(other.id)).lengthOfStay;
          int otherEnd = Math.min(otherStart + otherDuration - 1, input.Days() - 1);

          // Rumus overlap: StartA <= EndB && StartB <= EndA
          if (randomDay <= otherEnd && otherStart <= pEndDay) {
            patientsInTargetRoom.add(other);
          }
        }
      }

      // 6. Logika Kick:
      // Jika ada konflik Gender -> Kick SEMUA yang beda gender
      // Jika konflik Kapasitas -> Kick 1 orang random

      IHTP_Input.Gender pGender = input.patientsList.get(input.patientIndexById.get(p.id)).gender;
      int roomCapacity = input.roomsList.get(input.roomIndexById.get(randomRoom)).capacity;

      boolean kickedForGender = false;

      // Cek Gender Conflict
      for (OutputPatient existing : patientsInTargetRoom) {
        IHTP_Input.Gender existingGender = input.patientsList.get(input.patientIndexById.get(existing.id)).gender;
        if (existingGender != pGender) {
          existing.assignedDay = null; // KICK!
          existing.assignedRoom = null;
          existing.assignedTheater = null;
          kickedForGender = true;
        }
      }

      // Jika setelah kick gender masih penuh (atau tidak ada masalah gender tapi
      // penuh), kick random
      if (!kickedForGender) {
        // Hitung sisa orang di kamar (termasuk si P yang baru masuk)
        int currentOccupancy = 1; // P sudah dihitung 1
        List<OutputPatient> survivors = new ArrayList<>();

        for (OutputPatient existing : patientsInTargetRoom) {
          if (existing.assignedDay != null) { // Yang belum kena kick gender
            currentOccupancy++;
            survivors.add(existing);
          }
        }

        // Jika Over capacity, tendang 1 orang lagi secara acak
        if (currentOccupancy > roomCapacity && !survivors.isEmpty()) {
          OutputPatient victim = survivors.get(random.nextInt(survivors.size()));
          victim.assignedDay = null; // KICK!
          victim.assignedRoom = null;
          victim.assignedTheater = null;
        }
      }
    }

    /**
     * Assigns a random day/room/theater to a randomly chosen unscheduled patient.
     */
    private void assignRandomToUnscheduled(List<OutputPatient> solution) {
      List<Integer> unscheduled = new ArrayList<>();
      for (int i = 0; i < solution.size(); i++) {
        if (solution.get(i).assignedDay == null) {
          unscheduled.add(i);
        }
      }
      if (unscheduled.isEmpty())
        return;

      int idx = unscheduled.get(random.nextInt(unscheduled.size()));
      OutputPatient p = solution.get(idx);
      p.assignedDay = random.nextInt(input.Days());
      p.assignedRoom = input.RoomId(random.nextInt(input.Rooms()));
      p.assignedTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));
    }

    /** Moves a random patient (with an assignment) to a different random day. */
    private void movePatientToNewDay(List<OutputPatient> solution) {
      List<Integer> assigned = new ArrayList<>();
      for (int i = 0; i < solution.size(); i++) {
        if (solution.get(i).assignedDay != null) {
          assigned.add(i);
        }
      }
      if (assigned.isEmpty())
        return;

      int patientIndex = assigned.get(random.nextInt(assigned.size()));
      OutputPatient patient = solution.get(patientIndex);

      int currentDay = patient.assignedDay;
      int newDay;
      do {
        newDay = random.nextInt(input.Days());
      } while (newDay == currentDay);

      patient.assignedDay = newDay;
    }

    /** Changes the room assignment for a randomly chosen assigned patient. */
    private void changePatientRoom(List<OutputPatient> solution) {
      List<Integer> assigned = new ArrayList<>();
      for (int i = 0; i < solution.size(); i++) {
        if (solution.get(i).assignedDay != null) {
          assigned.add(i);
        }
      }
      if (assigned.isEmpty())
        return;

      int patientIndex = assigned.get(random.nextInt(assigned.size()));
      OutputPatient patient = solution.get(patientIndex);

      String currentRoom = patient.assignedRoom;
      String newRoom;
      do {
        newRoom = input.RoomId(random.nextInt(input.Rooms()));
      } while (newRoom.equals(currentRoom));

      patient.assignedRoom = newRoom;
    }

    /**
     * Changes the operating theater assignment for a randomly chosen assigned
     * patient.
     */
    private void changePatientTheater(List<OutputPatient> solution) {
      List<Integer> assigned = new ArrayList<>();
      for (int i = 0; i < solution.size(); i++) {
        if (solution.get(i).assignedDay != null) {
          assigned.add(i);
        }
      }
      if (assigned.isEmpty())
        return;

      int patientIndex = assigned.get(random.nextInt(assigned.size()));
      OutputPatient patient = solution.get(patientIndex);

      String currentTheater = patient.assignedTheater;
      String newTheater;
      do {
        newTheater = input.OperatingTheaterId(random.nextInt(input.OperatingTheaters()));
      } while (newTheater.equals(currentTheater));

      patient.assignedTheater = newTheater;
    }

    /** Swaps assignments between two randomly chosen assigned patients. */
    private void swapTwoPatients(List<OutputPatient> solution) {
      List<Integer> assigned = new ArrayList<>();
      for (int i = 0; i < solution.size(); i++) {
        if (solution.get(i).assignedDay != null) {
          assigned.add(i);
        }
      }
      if (assigned.size() < 2)
        return;

      int idx1 = random.nextInt(assigned.size());
      int idx2;
      do {
        idx2 = random.nextInt(assigned.size());
      } while (idx1 == idx2);

      int pIdx1 = assigned.get(idx1);
      int pIdx2 = assigned.get(idx2);

      OutputPatient p1 = solution.get(pIdx1);
      OutputPatient p2 = solution.get(pIdx2);

      Integer tempDay = p1.assignedDay;
      String tempRoom = p1.assignedRoom;
      String tempTheater = p1.assignedTheater;

      p1.assignedDay = p2.assignedDay;
      p1.assignedRoom = p2.assignedRoom;
      p1.assignedTheater = p2.assignedTheater;

      p2.assignedDay = tempDay;
      p2.assignedRoom = tempRoom;
      p2.assignedTheater = tempTheater;
    }

  }

  private static JSONArray nursesToJSONArray(IHTP_Input input, List<OutputNurse> nurses) {
    JSONArray arr = new JSONArray();
    for (OutputNurse out : nurses) {
      JSONObject nj = new JSONObject();
      nj.put("id", out.id);

      JSONArray assignments = new JSONArray();
      for (int i = 0; i < out.workingShifts.size(); i++) {
        JSONObject asg = new JSONObject();
        int shiftIdx = out.workingShifts.get(i);
        int day = shiftIdx / input.ShiftsPerDay();
        String shift = input.ShiftName(shiftIdx % input.ShiftsPerDay());

        JSONArray rooms = new JSONArray();
        for (String r : out.workingRooms.get(i)) {
          rooms.put(r);
        }

        asg.put("day", day);
        asg.put("shift", shift);
        asg.put("rooms", rooms);
        assignments.put(asg);
      }
      nj.put("assignments", assignments);
      arr.put(nj);
    }
    return arr;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: java IHTP_Solution <input.json> <maxMinutes> [violationLogFile] [solutionFile]");
      System.err.println("  input.json        - Input file path");
      System.err.println("  maxMinutes        - Maximum runtime in minutes");
      System.err.println("  violationLogFile  - Output CSV file for violation log (default: violation_log.csv)");
      System.err.println("  solutionFile      - Output JSON file for best solution (default: solution.json)");
      System.err.println();
      System.err.println("Examples:");
      System.err.println("  java IHTP_Solution input.json 2");
      System.err.println("  java IHTP_Solution input.json 2 my_violations.csv my_solution.json");
      System.err.println("  java IHTP_Solution input.json 2 violations.csv");
      return;
    }

    String inputFile = args[0];
    long maxRuntimeMinutes = Long.parseLong(args[1]);
    long maxRuntimeMillis = maxRuntimeMinutes * 60_000L; // convert to milliseconds

    // Parse optional output file arguments
    String violationLogFile = "violation_log.csv";
    String solutionFile = "solution.json";

    if (args.length >= 3 && !args[2].isEmpty()) {
      violationLogFile = args[2];
    }

    if (args.length >= 4 && !args[3].isEmpty()) {
      solutionFile = args[3];
    }

    // Validate file extensions
    if (!violationLogFile.toLowerCase().endsWith(".csv")) {
      System.err.println("Warning: Violation log file should have .csv extension. Current: " + violationLogFile);
    }

    if (!solutionFile.toLowerCase().endsWith(".json")) {
      System.err.println("Warning: Solution file should have .json extension. Current: " + solutionFile);
    }

    System.out.println("Configuration:");
    System.out.println("  Input file: " + inputFile);
    System.out.println("  Max runtime: " + maxRuntimeMinutes + " minutes");
    System.out.println("  Violation log: " + violationLogFile);
    System.out.println("  Solution file: " + solutionFile);
    System.out.println();

    // Initialize CSV log file (header will be written by PA_ILS after completing)
    FileWriter logWriter = new FileWriter(violationLogFile, false);

    long startTime = System.currentTimeMillis();

    // Get an initial solution first
    IHTP_Input input = new IHTP_Input(inputFile);
    IHTP_Preprocess preprocessor = new IHTP_Preprocess(input);
    PriorityQueue<IHTP_Input.Patient> patientQueue = preprocessor.queuePatientsBySlots();

    IHTP_Generate_Solution generator = new IHTP_Generate_Solution(input);
    generator.generateInitialSolution(patientQueue);

    // Check initial violations
    int initialUnscheduled = countUnscheduledMandatoryPatients(generator.outputPatients, input);
    System.out.println("Initial unscheduled mandatory patients: " + initialUnscheduled);

    // ========== PA-ILS PHASE (MAIN ALGORITHM) ==========
    // PA-ILS is now the MAIN algorithm for finding FEASIBLE solution (0 hard
    // violations)
    System.out.println("\n=== Starting PA-ILS Phase (Main Algorithm) ===");
    System.out.println("Goal: Achieve 0 TOTAL hard constraint violations (Feasible Solution)");

    // Give PA-ILS 8 minutes or 80% of runtime to find feasible solution
    long paIlsTimeLimit = Math.min((long) (maxRuntimeMillis * 0.8), 8 * 60 * 1000);
    System.out.println("PA-ILS time limit: " + (paIlsTimeLimit / 1000) + " seconds");

    PA_ILS paIls = new PA_ILS(input, System.nanoTime());
    List<OutputPatient> feasibleSolution = paIls.searchFeasibleSolution(generator.outputPatients,
        generator.outputNurses, paIlsTimeLimit);

    // Write PA-ILS violation logs to file
    System.out.println("Writing PA-ILS violation logs to file...");
    logWriter.write(PA_ILS.getCsvHeader() + "\n");
    for (PA_ILS.ViolationLogEntry entry : paIls.getViolationLogs()) {
      logWriter.write(entry.toCsvLine() + "\n");
    }
    logWriter.flush();
    System.out.println("PA-ILS logs written: " + paIls.getViolationLogs().size() + " entries");

    // Update generator's solution with PA-ILS result
    generator.outputPatients.clear();
    generator.outputPatients.addAll(feasibleSolution);

    // Validate PA-ILS result
    JSONObject paIlsOutput = new JSONObject();
    paIlsOutput.put("patients", generator.getOutputPatients(feasibleSolution));
    paIlsOutput.put("nurses", generator.getOutputNurses());
    IHTP_Validator paIlsValidator = new IHTP_Validator(input, paIlsOutput, true);
    int paIlsViolations = paIlsValidator.getTotalViolations();

    long paIlsTime = System.currentTimeMillis() - startTime;
    System.out.println("\n=== PA-ILS Phase Complete ===");
    System.out.println("PA-ILS runtime: " + (paIlsTime / 1000) + " seconds");
    System.out.println("PA-ILS total violations: " + paIlsViolations);

    // Save PA-ILS solution
    saveSolution(generator, feasibleSolution, solutionFile);
    System.out.println("PA-ILS solution saved to: " + solutionFile);

    // If PA-ILS achieved feasibility, we can optionally run ILS for soft cost
    // optimization
    if (paIlsViolations == 0) {
      System.out.println("\n*** PA-ILS ACHIEVED FEASIBILITY! (0 Hard Violations) ***");
      System.out.println("Remaining time will be used for soft cost optimization...");
    } else {
      System.out.println("\nPA-ILS did not achieve full feasibility.");
      System.out.println("Remaining violations: " + paIlsViolations);
    }

    System.out.println("\nStarting single-threaded ILS for optimization...");

    // Run single ILS with custom output files
    ILSResult finalResult = runSingleThreadedILS(input, generator, maxRuntimeMillis, startTime, logWriter,
        solutionFile);

    long elapsedMin = (System.currentTimeMillis() - startTime) / 60_000;
    System.out.println("ILS completed after " + elapsedMin + " minutes.");

    if (finalResult != null && finalResult.violations == 0) {
      System.out.println("Optimal solution found with 0 violations!");
      System.out.println("Solution saved to: " + solutionFile);
    } else if (finalResult != null) {
      System.out.println("Best solution found with " + finalResult.violations + " violations.");
      System.out.println("Solution saved to: " + solutionFile);
    } else {
      System.out.println("No solution found within the time limit.");
    }

    System.out.println("Violation log saved to: " + violationLogFile);
    logWriter.close();
  }

  // Helper method to count unscheduled mandatory patients
  private static int countUnscheduledMandatoryPatients(List<OutputPatient> solution, IHTP_Input input) {
    int count = 0;
    for (OutputPatient p : solution) {
      if (p.assignedDay == null) {
        Integer idx = input.patientIndexById.get(p.id);
        if (idx != null) {
          IHTP_Input.Patient patientData = input.patientsList.get(idx);
          if (patientData.mandatory) {
            count++;
          }
        }
      }
    }
    return count;
  }

  // Helper container for restart results
  private static class RestartResult {
    final List<OutputPatient> currentSolution;
    final List<OutputNurse> currentNurseSolution;
    final int currentViolations;
    final IHTP_Generate_Solution generator;

    RestartResult(List<OutputPatient> cur, List<OutputNurse> curNurses, int viol, IHTP_Generate_Solution gen) {
      this.currentSolution = cur;
      this.currentNurseSolution = curNurses;
      this.currentViolations = viol;
      this.generator = gen;
    }
  }

  // Build a fresh initial solution (used by restarts) - now with PA-ILS
  // integration
  private static RestartResult performRestart(IHTP_Input input) {
    IHTP_Preprocess pre = new IHTP_Preprocess(input);
    PriorityQueue<IHTP_Input.Patient> pq = pre.queuePatientsBySlots();

    IHTP_Generate_Solution gen = new IHTP_Generate_Solution(input);
    gen.generateInitialSolution(pq);

    List<OutputPatient> cur = deepCopyPatients(gen.outputPatients);
    List<OutputNurse> curNurses = new ArrayList<>(gen.outputNurses);

    // Apply PA-ILS if there are unscheduled mandatory patients
    int unscheduled = countUnscheduledMandatoryPatients(cur, input);
    if (unscheduled > 0) {
      PA_ILS paIls = new PA_ILS(input, System.nanoTime());
      cur = paIls.searchFeasibleSolution(cur, curNurses, 30_000); // 30 seconds max for restart
      gen.outputPatients.clear();
      gen.outputPatients.addAll(cur);
    }

    JSONObject out = new JSONObject();
    out.put("patients", gen.getOutputPatients(cur));
    out.put("nurses", gen.getOutputNurses());

    IHTP_Validator v = new IHTP_Validator(input, out, false);
    int viol = v.getTotalViolations();

    return new RestartResult(cur, curNurses, viol, gen);
  }

  // Build a fresh initial solution using RANDOM-WEIGHTED preprocess (used by
  // alternating restarts) - now with PA-ILS integration
  private static RestartResult performRestartWeighted(IHTP_Input input, long seed, double minW, double maxW,
      boolean includeOptional // biasanya false agar baseline fokus mandatory
  ) {
    IHTP_Preprocess pre = new IHTP_Preprocess(input);
    PriorityQueue<IHTP_Input.Patient> pq = pre.queuePatientsByRandomWeightedSlots(seed, minW, maxW, includeOptional);

    for (IHTP_Input.Patient p : pq) {
      System.out.printf("Patient %s has %d available slots%n", p.id, p.maxSlot);
    }

    IHTP_Generate_Solution gen = new IHTP_Generate_Solution(input);
    gen.generateInitialSolution(pq);

    List<OutputPatient> cur = deepCopyPatients(gen.outputPatients);
    List<OutputNurse> curNurses = new ArrayList<>(gen.outputNurses);

    // Apply PA-ILS if there are unscheduled mandatory patients
    int unscheduled = countUnscheduledMandatoryPatients(cur, input);
    if (unscheduled > 0) {
      PA_ILS paIls = new PA_ILS(input, System.nanoTime());
      cur = paIls.searchFeasibleSolution(cur, curNurses, 30_000); // 30 seconds max for restart
      gen.outputPatients.clear();
      gen.outputPatients.addAll(cur);
    }

    JSONObject out = new JSONObject();
    out.put("patients", gen.getOutputPatients(cur));
    out.put("nurses", gen.getOutputNurses());

    IHTP_Validator v = new IHTP_Validator(input, out, false);
    int viol = v.getTotalViolations();

    return new RestartResult(cur, curNurses, viol, gen);
  }

  private static String getViolationBreakdown(IHTP_Input input, IHTP_Generate_Solution gen,
      List<OutputPatient> patients, List<OutputNurse> nurses) {
    List<OutputNurse> oldNurses = gen.outputNurses;
    gen.outputNurses = nurses;
    JSONObject out = new JSONObject();
    out.put("patients", gen.getOutputPatients(patients));
    out.put("nurses", gen.getOutputNurses());
    gen.outputNurses = oldNurses;
    IHTP_Validator v = new IHTP_Validator(input, out, false);
    return v.getVMandatoryUnsch() + "," + v.getVRoomGender() + "," + v.getVRoomCompat() + "," + v.getVSurgeonOver()
        + "," + v.getVOTOver() + "," + v.getVAdmissionDay() + "," + v.getVRoomCap() + "," + v.getVNursePres() + ","
        + v.getVUncovered();
  }

  private static ILSResult runSingleThreadedILS(IHTP_Input input, IHTP_Generate_Solution localGenerator,
      long maxRuntimeMillis, long globalStartTime, FileWriter logWriter, String solutionFile) {
    try {
      // Write Header
      // logWriter.write(
      // "Iteration,Time,Violations,MandatoryUnsch,RoomGender,Compat,SurgeonOver,OTOver,AdmissionDay,RoomCap,NursePres,Uncovered,Message\n");
      // ---------- initial baseline from provided generator ----------
      List<OutputPatient> currentSolution = new ArrayList<>(localGenerator.outputPatients);
      List<OutputNurse> currentNurseSolution = new ArrayList<>(localGenerator.outputNurses);

      JSONObject initialOut = new JSONObject();
      initialOut.put("patients", localGenerator.getOutputPatients(currentSolution));
      initialOut.put("nurses", localGenerator.getOutputNurses());

      IHTP_Validator initialValidator = new IHTP_Validator(input, initialOut, false);
      int currentViolations = initialValidator.getTotalViolations();

      long initialTime = System.currentTimeMillis() - globalStartTime;
      String breakdownInit = getViolationBreakdown(input, localGenerator, currentSolution, currentNurseSolution);
      // logWriter.write("0," + initialTime + "," + currentViolations + "," +
      // breakdownInit + ",INITIAL\n");
      // logWriter.flush();

      System.out.println("Initial solution violations: " + currentViolations);
      try {
        Files.writeString(Paths.get(solutionFile), initialOut.toString(2));
        System.out.println("Initial solution saved to: " + solutionFile);
      } catch (IOException e) {
        System.err.println("Error saving initial solution: " + e.getMessage());
      }

      // ---------- ILS state ----------
      ILS_Solution ilsSolution = new ILS_Solution(input, currentSolution, currentNurseSolution, currentViolations);

      List<OutputPatient> globalBestSolution = new ArrayList<>(currentSolution);
      int globalBestViolations = currentViolations;

      int iteration = 0; // accepted moves
      int noImprovementCount = 0; // consecutive non-improving steps

      // PA-ILS params
      double pNonRandom = 0.9;
      double decreasingValue = 0.9;
      double constantFactor = 0.0; // not used in linear mode
      int updates = 0;
      int limitUpdateProbabilityNonRandomTimeSlot = 200;

      // Stuck/restart controls - tuned for faster convergence
      final int LIMIT_STUCK_ITERS = 300;
      final long TIMEOUT_STUCK_MS = 15_000L;
      final int MAX_RESTARTS = 100000000;
      int restarts = 0;
      long lastAcceptTs = System.currentTimeMillis();

      while ((System.currentTimeMillis() - globalStartTime) < maxRuntimeMillis && globalBestViolations > 0) {
        try {
          // --- STUCK CHECK ---
          boolean timeStuck = (System.currentTimeMillis() - lastAcceptTs) > TIMEOUT_STUCK_MS;
          boolean iterStuck = noImprovementCount > LIMIT_STUCK_ITERS;
          if (timeStuck || iterStuck) {
            if (restarts < MAX_RESTARTS) {
              restarts++;
              long now = System.currentTimeMillis() - globalStartTime;
              String breakdownRestart = getViolationBreakdown(input, localGenerator, currentSolution,
                  currentNurseSolution);
              // logWriter.write(iteration + "," + now + "," + currentViolations + "," +
              // breakdownRestart + ",RESTART_"
              // + restarts + "\n");
              // logWriter.flush();
              System.out.println("=== RESTART #" + restarts + " ===");

              boolean useWeighted = (restarts % 2 == 1); // selang-seling: restart #1 weighted, #2 standard, dst.
              RestartResult rr;
              if (useWeighted) {
                long seed = System.nanoTime() ^ ((long) restarts << 21) ^ (long) currentViolations;
                double minW = 0.85, maxW = 1.15; // start conservative
                if (restarts >= 2) {
                  minW = 0.75;
                  maxW = 1.25;
                }
                if (restarts >= 5) {
                  minW = 0.65;
                  maxW = 1.35;
                }
                if (restarts >= 10) {
                  minW = 0.55;
                  maxW = 1.45;
                }
                boolean includeOptional = false; // baseline fokus mandatory (ubah ke true bila ingin)
                System.out.println("Restart mode: WEIGHTED (seed=" + seed + ", w=" + minW + ".." + maxW
                    + ", includeOptional=" + includeOptional + ")");
                rr = performRestartWeighted(input, seed, minW, maxW, includeOptional);
              } else {
                System.out.println("Restart mode: STANDARD (queuePatientsBySlots)");
                rr = performRestart(input);
              }
              localGenerator = rr.generator; // swap generator to the fresh one
              currentSolution = rr.currentSolution;
              currentNurseSolution = rr.currentNurseSolution;
              currentViolations = rr.currentViolations;

              // update global best if restart baseline is better
              if (currentViolations < globalBestViolations) {
                globalBestViolations = currentViolations;
                globalBestSolution = deepCopyPatients(currentSolution);
                try {
                  JSONObject out = new JSONObject();
                  out.put("patients", localGenerator.getOutputPatients(currentSolution));
                  out.put("nurses", localGenerator.getOutputNurses());
                  Files.writeString(Paths.get(solutionFile), out.toString(2));
                } catch (IOException e) {
                  System.err.println("Error saving restarted baseline: " + e.getMessage());
                }
              }

              // reset PA-ILS controls
              pNonRandom = 0.9;
              updates = 0;
              noImprovementCount = 0;
              lastAcceptTs = System.currentTimeMillis();

              continue; // go next loop with fresh baseline
            } else {
              // can't restart more → at least shuffle a bit and continue
              currentSolution = ilsSolution.shuffleSmall(currentSolution, 3, pNonRandom);
              noImprovementCount = 0;
              lastAcceptTs = System.currentTimeMillis();
            }
          }

          // --- PA-ILS step ---
          List<OutputPatient> perturbed = ilsSolution.getNeighborPAILS(currentSolution, pNonRandom);

          // local search
          List<OutputPatient> improved = ilsSolution.localSearch(perturbed, currentNurseSolution);
          int newViolations = ilsSolution.evaluateSolution(improved, currentNurseSolution);

          boolean accept;
          String status;
          boolean shouldLog = true;
          if (newViolations < currentViolations) {
            accept = true;
            status = "BETTER";
            noImprovementCount = 0;
          } else if (newViolations == currentViolations) {
            accept = true;
            status = "SAME";
            noImprovementCount++;
          } else {
            accept = false;
            status = "WORSE_REJECTED";
            noImprovementCount++;
          }

          if (accept) {
            iteration++;
            currentSolution = deepCopyPatients(improved);
            currentViolations = newViolations;
            lastAcceptTs = System.currentTimeMillis();

            long now = lastAcceptTs - globalStartTime;

            if (newViolations < globalBestViolations) {
              globalBestViolations = newViolations;
              globalBestSolution = deepCopyPatients(improved);
              status += "_GLOBAL_BEST";

              try {
                JSONObject j_out = new JSONObject();
                j_out.put("patients", localGenerator.getOutputPatients(improved));
                j_out.put("nurses", localGenerator.getOutputNurses());
                IHTP_Validator verifier = new IHTP_Validator(input, j_out, false);
                int actual = verifier.getTotalViolations();
                if (actual <= newViolations) {
                  Files.writeString(Paths.get(solutionFile), j_out.toString(2));
                  System.out.println("New global best: " + actual + " violations (saved to " + solutionFile + ")");
                  if (actual == 0) {
                    status += "_OPTIMAL";
                    optimalSolutionFound.set(true);
                    System.out.println("OPTIMAL SOLUTION FOUND!");
                  }
                }
              } catch (IOException e) {
                System.err.println("Error saving global best to " + solutionFile + ": " + e.getMessage());
              }
            }

            String breakdownIter = getViolationBreakdown(input, localGenerator, currentSolution, currentNurseSolution);
            // logWriter.write(iteration + "," + now + "," + newViolations + "," +
            // breakdownIter + "," + status + "\n");
            // logWriter.flush();

            if (iteration % 100 == 0) {
              System.out.println("Iteration " + iteration + ": " + newViolations + " violations (" + status + ")");
            }

            if (globalBestViolations == 0) {
              System.out.println("Optimal solution found! Stopping ILS.");
              break;
            }
          }

          // periodic pNonRandom update (by accepted-iteration count)
          if (iteration > 0 && iteration % limitUpdateProbabilityNonRandomTimeSlot == 0) {
            updates++;
            pNonRandom = calculateProbability(pNonRandom, decreasingValue, constantFactor, updates);
          }

          if (iteration % 200 == 0) {
            List<String> unscheduled = IHTP_Input.getUnscheduledPatientIds(currentSolution);
            if (!unscheduled.isEmpty()) {
              System.out.println("Unscheduled patients: " + unscheduled.size());
            }
          }

        } catch (Exception iterException) {
          System.err.println("Error in iteration " + iteration + ": " + iterException.getMessage());
          // try a restart rather than abort
          if (restarts < MAX_RESTARTS) {
            restarts++;
            long now = System.currentTimeMillis() - globalStartTime;
            // try {
            // logWriter.write(iteration + "," + now + "," + currentViolations + ",RESTART_"
            // + restarts + "\n");
            // logWriter.flush();
            // } catch (IOException ioe) {
            // System.err.println("Error logging restart: " + ioe.getMessage());
            // }
            RestartResult rr = performRestart(input);
            localGenerator = rr.generator;
            currentSolution = rr.currentSolution;
            currentNurseSolution = rr.currentNurseSolution;
            currentViolations = rr.currentViolations;

            // reset counters
            pNonRandom = 0.9;
            updates = 0;
            noImprovementCount = 0;
            lastAcceptTs = System.currentTimeMillis();
          } else {
            break;
          }
        }
      }

      long totalTime = System.currentTimeMillis() - globalStartTime;
      System.out.println("ILS completed: " + globalBestViolations + " violations after " + iteration
          + " accepted iterations in " + (totalTime / 1000.0) + " seconds");

      // logWriter.write("FINAL," + totalTime + "," + globalBestViolations +
      // ",COMPLETED\n");
      // logWriter.flush();

      List<String> unscheduledFinal = IHTP_Input.getUnscheduledPatientIds(globalBestSolution);
      if (!unscheduledFinal.isEmpty()) {
        System.out.println("Final unscheduled patients: " + unscheduledFinal.size());
      }

      return new ILSResult(globalBestSolution, currentNurseSolution, globalBestViolations);

    } catch (Exception e) {
      System.err.println("Fatal error in single-threaded ILS: " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  // Helper method to deep copy patient list
  private static List<OutputPatient> deepCopyPatients(List<OutputPatient> solution) {
    List<OutputPatient> copy = new ArrayList<>();
    for (OutputPatient p : solution) {
      OutputPatient newP = new OutputPatient();
      newP.id = p.id;
      newP.assignedDay = p.assignedDay;
      newP.assignedRoom = p.assignedRoom;
      newP.assignedTheater = p.assignedTheater;
      copy.add(newP);
    }
    return copy;
  }

  private static double calculateProbability(double p, double decreasingValue, double constantFactor, int updates) {
    // Versi sederhana: p *= decreasingValue (mis. 0.9)
    double pNew = p * decreasingValue;

    // (Opsional) versi eksponensial jika ingin pakai constantFactor:
    // double pFloor = 0.05;
    // double pNew = pFloor + (p - pFloor) * Math.exp(-constantFactor * updates);

    // Jaga di [0.05, 0.95]
    return Math.max(0.05, Math.min(0.95, pNew));
  }

  // Helper method to save solution to file
  private static void saveSolution(IHTP_Generate_Solution generator, List<OutputPatient> patients,
      String solutionFile) {
    try {
      JSONObject output = new JSONObject();
      output.put("patients", generator.getOutputPatients(patients));
      output.put("nurses", generator.getOutputNurses());

      java.io.FileWriter writer = new java.io.FileWriter(solutionFile);
      writer.write(output.toString(2));
      writer.close();
    } catch (Exception e) {
      System.err.println("Error saving solution: " + e.getMessage());
    }
  }

  // --- External Entry Point for Optimizer ---
  public static boolean solve(String inputPath, int timeLimitMinutes, String outputDetailsPath,
      String outputSolutionPath) {
    try {
      System.out.println(">>> IHTP_Solution: Starting Feasibility Search (PA-ILS)...");

      // 1. Setup
      IHTP_Input input = new IHTP_Input(inputPath);
      IHTP_Preprocess preprocessor = new IHTP_Preprocess(input);
      PriorityQueue<IHTP_Input.Patient> patientQueue = preprocessor.queuePatientsBySlots();
      IHTP_Generate_Solution generator = new IHTP_Generate_Solution(input);

      // 2. Initial Constructive
      generator.generateInitialSolution(patientQueue);

      // 3. PA-ILS (Feasibility Phase)
      long maxRuntimeMillis = timeLimitMinutes * 60_000L;
      PA_ILS paIls = new PA_ILS(input, System.nanoTime());
      List<OutputPatient> feasibleSolution = paIls.searchFeasibleSolution(generator.outputPatients,
          generator.outputNurses, maxRuntimeMillis);

      // 4. Update Generator State
      generator.outputPatients.clear();
      generator.outputPatients.addAll(feasibleSolution);

      // 5. Check Feasibility
      JSONObject paIlsOutput = new JSONObject();
      paIlsOutput.put("patients", generator.getOutputPatients(feasibleSolution));
      paIlsOutput.put("nurses", generator.getOutputNurses());
      IHTP_Validator paIlsValidator = new IHTP_Validator(input, paIlsOutput, false); // false = quiet
      int violations = paIlsValidator.getTotalViolations();

      System.out.println(">>> IHTP_Solution: Feasibility Search Complete. Violations: " + violations);

      // Save result
      saveSolution(generator, feasibleSolution, outputSolutionPath);

      return (violations == 0);

    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  static class ILSResult {
    List<OutputPatient> patients;
    List<OutputNurse> nurses;
    int violations;

    public ILSResult(List<OutputPatient> patients, List<OutputNurse> nurses, int violations) {
      this.patients = patients;
      this.nurses = nurses;
      this.violations = violations;
    }
  }
}