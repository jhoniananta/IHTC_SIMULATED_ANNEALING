// PA_ILS.java - Progressive Acceptance Iterated Local Search
// Combined with Multi-Neighborhood Lexicographic approach from IHTC 2024
// Goal: Achieve TOTAL 0 hard constraint violations (Feasible Solution)

import java.util.*;
import org.json.*;

/**
 * PA-ILS + Multi-Neighborhood Lexicographic Local Search
 * 
 * Based on IHTC 2024 approach: A. Representation that enforces some hard
 * constraints (H5, H8, H9) B. Domain pruning for H2, H3, H4, H6 C.
 * Lexicographic objective: (HardViolations, SoftCost) - hard first D.
 * Multi-neighborhood: Repair, Swap, Kick, KickOut, FixGender, FixCapacity E.
 * Incremental evaluation F. PA-ILS: perturbation + local search without
 * acceptance criteria
 */
public class PA_ILS {

    /**
     * Inner class untuk menyimpan log violation setiap iterasi Format CSV:
     * iteration,time_ms,violations,status
     */
    public static class ViolationLogEntry {
        public final int iteration;
        public final long timeMs;
        public final int violations;
        public final String status;

        public ViolationLogEntry(int iteration, long timeMs, int violations, String status) {
            this.iteration = iteration;
            this.timeMs = timeMs;
            this.violations = violations;
            this.status = status;
        }

        public String toCsvLine() {
            return iteration + "," + timeMs + "," + violations + "," + status;
        }
    }

    private final IHTP_Input input;
    private final Random random;

    // Violation log storage - setiap iterasi disimpan ke sini, baru ditulis ke file
    // saat selesai
    private final List<ViolationLogEntry> violationLogs = new ArrayList<>();
    private long startTimeMs;

    // PA-ILS Parameters from BAB 4
    private double decreasingValue = 0.3;
    private final double constantFactor = 0.9;
    private final int limitUpdateProbability = 1;
    private final int limitStuck = 8;
    private final int limitShuffle = 100;

    private double probabilityNonRandomTimeSlot;

    // Solution tracking
    private List<IHTP_Solution.OutputPatient> currentSolution;
    private List<IHTP_Solution.OutputNurse> currentNurseSolution;
    private List<IHTP_Solution.OutputPatient> bestSolution;
    private int bestTotalHardViolations = Integer.MAX_VALUE;

    // Domain pruning structures
    private Map<String, Set<Integer>> validDaysForPatient;
    private Map<String, Set<Integer>> validRoomsForPatient;
    private Map<String, Set<Integer>> validTheatersForPatient;

    // Constraint tracking for incremental evaluation
    private Map<String, Map<Integer, List<IHTP_Solution.OutputPatient>>> roomDayPatients; // roomId -> day -> patients
    private Map<Integer, Map<String, Integer>> surgeonDayUsage; // day -> surgeonId -> minutes
    private Map<Integer, Map<String, Integer>> theaterDayUsage; // day -> theaterId -> minutes

    public PA_ILS(IHTP_Input input, long seed) {
        this.input = input;
        this.random = new Random(seed);
        initializeDataStructures();
    }

    private void initializeDataStructures() {
        validDaysForPatient = new HashMap<>();
        validRoomsForPatient = new HashMap<>();
        validTheatersForPatient = new HashMap<>();
        roomDayPatients = new HashMap<>();
        surgeonDayUsage = new HashMap<>();
        theaterDayUsage = new HashMap<>();
    }

    /**
     * Domain Pruning - Key to achieving feasibility Removes assignments that would
     * definitely cause hard violations
     */
    private void performDomainPruning() {
        for (IHTP_Input.Patient p : input.patientsList) {
            Set<Integer> validDays = new HashSet<>();
            Set<Integer> validRooms = new HashSet<>();
            Set<Integer> validTheaters = new HashSet<>();

            // H6: Valid admission days
            // For mandatory: surgeryReleaseDay to surgeryDueDay
            // For optional: surgeryReleaseDay to days-1 (no surgeryDueDay constraint)
            int startDay = p.surgeryReleaseDay;
            int endDay = p.mandatory ? Math.min(p.surgeryDueDay, input.Days() - 1) : (input.Days() - 1);

            for (int day = startDay; day <= endDay; day++) {
                // H3: Check surgeon has capacity
                IHTP_Input.Surgeon surgeon = input.surgeonsList.get(p.surgeon);
                if (surgeon.maxSurgeryTime[day] >= p.surgeryDuration) {
                    // Check at least one theater has capacity on this day
                    boolean hasTheater = false;
                    for (int t = 0; t < input.OperatingTheaters(); t++) {
                        if (input.theatersList.get(t).availability[day] >= p.surgeryDuration) {
                            hasTheater = true;
                            break;
                        }
                    }
                    if (hasTheater) {
                        validDays.add(day);
                    }
                }
            }

            // H2: Compatible rooms
            for (int r = 0; r < input.Rooms(); r++) {
                boolean compatible = true;
                if (p.incompatibleRooms != null && r < p.incompatibleRooms.length) {
                    compatible = !p.incompatibleRooms[r];
                }
                if (compatible) {
                    validRooms.add(r);
                }
            }

            // H4: Valid theaters per valid day
            for (int day : validDays) {
                for (int t = 0; t < input.OperatingTheaters(); t++) {
                    if (input.theatersList.get(t).availability[day] >= p.surgeryDuration) {
                        validTheaters.add(t);
                    }
                }
            }

            validDaysForPatient.put(p.id, validDays);
            validRoomsForPatient.put(p.id, validRooms);
            validTheatersForPatient.put(p.id, validTheaters);
        }
    }

    /**
     * Build room-day-patient tracking structure
     */
    private void buildRoomDayTracking() {
        roomDayPatients.clear();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            for (int day = p.assignedDay; day < p.assignedDay + data.lengthOfStay && day < input.Days(); day++) {
                roomDayPatients.computeIfAbsent(p.assignedRoom, k -> new HashMap<>())
                        .computeIfAbsent(day, k -> new ArrayList<>()).add(p);
            }
        }
    }

    /**
     * For PA-ILS Phase 1: ONLY count H5 (unscheduled mandatory) for speed PA-ILS
     * focuses solely on getting all mandatory patients scheduled
     */
    private int countMandatoryUnscheduled(List<IHTP_Solution.OutputPatient> solution) {
        int count = 0;
        for (IHTP_Solution.OutputPatient p : solution) {
            if (p.assignedDay == null) {
                IHTP_Input.Patient data = getPatientData(p.id);
                if (data != null && data.mandatory) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Count ALL hard constraint violations - Full evaluation for final verification
     * Returns breakdown: [total, H5, H1, H7, H6, H2, H3, H4]
     */
    /**
     * Count ALL hard constraint violations using robust Array-based Grid Breakdown:
     * [total, H5, H1, H7, H6, H2, H3, H4]
     */
    // Base occupancy data (from fixed occupants)
    private int[][] baseRoomOccupancy;
    private int[][] baseRoomGenderA;
    private int[][] baseRoomGenderB;

    private void precomputeOccupantData() {
        int R = input.roomsList.size();
        int D = input.Days();
        baseRoomOccupancy = new int[R][D];
        baseRoomGenderA = new int[R][D];
        baseRoomGenderB = new int[R][D];

        for (IHTP_Input.Occupant occ : input.occupantsList) {
            int r = occ.assignedRoom;
            for (int d = 0; d < occ.lengthOfStay && d < D; d++) {
                baseRoomOccupancy[r][d]++;
                if (occ.gender == IHTP_Input.Gender.A) {
                    baseRoomGenderA[r][d]++;
                } else {
                    baseRoomGenderB[r][d]++;
                }
            }
        }
    }

    /**
     * Count ALL hard constraint violations using robust Array-based Grid + Base
     * Occupants Breakdown: [total, H5, H1, H7, H6, H2, H3, H4]
     */
    private int[] countHardViolations(List<IHTP_Solution.OutputPatient> solution) {
        int h5 = 0, h1 = 0, h7 = 0, h6 = 0, h2 = 0, h3 = 0, h4 = 0;
        int numRooms = input.roomsList.size();
        int numDays = input.Days();

        // initialize with base
        int[][] occCount = new int[numRooms][numDays];
        int[][] genderA = new int[numRooms][numDays];
        int[][] genderB = new int[numRooms][numDays];

        for (int r = 0; r < numRooms; r++) {
            System.arraycopy(baseRoomOccupancy[r], 0, occCount[r], 0, numDays);
            System.arraycopy(baseRoomGenderA[r], 0, genderA[r], 0, numDays);
            System.arraycopy(baseRoomGenderB[r], 0, genderB[r], 0, numDays);
        }

        // Surgeon/Theater loads
        int[][] surgeonLoad = new int[input.Surgeons()][numDays];
        int[][] theaterLoad = new int[input.OperatingTheaters()][numDays];

        for (IHTP_Solution.OutputPatient p : solution) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            if (p.assignedDay == null) {
                if (data.mandatory)
                    h5++;
                continue;
            }

            // H6: Admission
            int lastDay = data.mandatory ? data.surgeryDueDay : (numDays - 1);
            if (p.assignedDay < data.surgeryReleaseDay || p.assignedDay > lastDay) {
                h6++;
            }

            // Loads
            surgeonLoad[data.surgeon][p.assignedDay] += data.surgeryDuration;
            if (p.assignedTheater != null) {
                Integer tIdx = input.theaterIndexById.get(p.assignedTheater);
                if (tIdx != null)
                    theaterLoad[tIdx][p.assignedDay] += data.surgeryDuration;
            }

            // H2 & Grid population
            if (p.assignedRoom != null) {
                Integer rIdx = input.roomIndexById.get(p.assignedRoom);
                if (rIdx != null) {
                    // Check H2
                    if (data.incompatibleRooms != null && rIdx < data.incompatibleRooms.length
                            && data.incompatibleRooms[rIdx]) {
                        h2++;
                    }
                    // Add to grid
                    for (int d = p.assignedDay; d < p.assignedDay + data.lengthOfStay && d < numDays; d++) {
                        occCount[rIdx][d]++;
                        if (data.gender == IHTP_Input.Gender.A)
                            genderA[rIdx][d]++;
                        else
                            genderB[rIdx][d]++;
                    }
                }
            }
        }

        // Check Room Constraints (H1, H7)
        for (int r = 0; r < numRooms; r++) {
            int capacity = input.roomsList.get(r).capacity;
            for (int d = 0; d < numDays; d++) {
                // H7: Capacity
                if (occCount[r][d] > capacity) {
                    h7 += (occCount[r][d] - capacity);
                }

                // H1: Gender (Weighted by minority)
                if (genderA[r][d] > 0 && genderB[r][d] > 0) {
                    h1 += Math.min(genderA[r][d], genderB[r][d]);
                }
            }
        }

        // Check Loads (H3)
        for (int s = 0; s < input.Surgeons(); s++) {
            for (int d = 0; d < numDays; d++) {
                if (surgeonLoad[s][d] > input.surgeonsList.get(s).maxSurgeryTime[d]) {
                    h3 += (surgeonLoad[s][d] - input.surgeonsList.get(s).maxSurgeryTime[d]);
                }
            }
        }

        // Check Loads (H4)
        for (int t = 0; t < input.OperatingTheaters(); t++) {
            for (int d = 0; d < numDays; d++) {
                if (theaterLoad[t][d] > input.theatersList.get(t).availability[d]) {
                    h4 += (theaterLoad[t][d] - input.theatersList.get(t).availability[d]);
                }
            }
        }

        int total = h5 + h1 + h7 + h6 + h2 + h3 + h4;
        return new int[] { total, h5, h1, h7, h6, h2, h3, h4 };
    }

    /**
     * Main PA-ILS procedure - Search for FEASIBLE solution (0 total hard
     * violations)
     */
    public List<IHTP_Solution.OutputPatient> searchFeasibleSolution(List<IHTP_Solution.OutputPatient> initialSolution,
            List<IHTP_Solution.OutputNurse> nurseSolution, long maxTimeMs) {

        // Clear previous logs and record start time
        violationLogs.clear();
        startTimeMs = System.currentTimeMillis();

        // Domain pruning
        performDomainPruning();

        this.currentSolution = deepCopy(initialSolution);
        this.currentNurseSolution = nurseSolution;
        this.bestSolution = deepCopy(initialSolution);

        buildRoomDayTracking();
        precomputeOccupantData();

        // For PA-ILS Phase 1: Focus ONLY on H5 (mandatory unscheduled)
        int currentH5 = countMandatoryUnscheduled(currentSolution);
        int bestH5 = currentH5;

        long startTime = System.currentTimeMillis();
        int iteration = 0;
        int noProgressCount = 0;
        final int MAX_NO_PROGRESS = 200;

        decreasingValue = 0.3;
        calculateProbability();

        System.out.println("=== PA-ILS Phase 1: Schedule All Mandatory Patients ===");
        System.out.println("Initial unscheduled mandatory: " + currentH5);

        // Main loop - Phase 1: Focus on H5 = 0
        // Initial full check
        int[] initialViolations = countHardViolations(currentSolution);
        bestTotalHardViolations = initialViolations[0];
        int currentTotalHard = bestTotalHardViolations;

        // Log initial state
        violationLogs.add(new ViolationLogEntry(0, 0, initialViolations[0], "INITIAL"));

        // Main loop - Phase 1 & 2: Target H5=0 first, then ALL Hard=0
        while (bestTotalHardViolations > 0) {
            if (System.currentTimeMillis() - startTime > maxTimeMs) {
                System.out.println("PA-ILS: Time limit reached. Best violations: " + bestTotalHardViolations);
                // Log timeout
                violationLogs.add(new ViolationLogEntry(iteration, System.currentTimeMillis() - startTimeMs,
                        bestTotalHardViolations, "TIMEOUT"));
                break;
            }

            // Check current status
            currentH5 = countMandatoryUnscheduled(currentSolution);

            if (currentH5 > 0) {
                // Phase 1 strategy: Aggressively schedule mandatory
                performPhase1Perturbation();
                performPhase1LocalSearch();
            } else {
                // Phase 2 strategy: Fix other violations while keeping H5=0
                int[] violations = countHardViolations(currentSolution);
                performWeightedPerturbation(violations);
                // Perform local search targeting specific violations
                if (violations[6] > 0)
                    fixSurgeonOvertimeMove(); // H3
                if (violations[7] > 0)
                    fixTheaterOvertimeMove(); // H4
                if (violations[3] > 0)
                    fixCapacityMove(); // H7
                if (violations[2] > 0)
                    fixGenderMove(); // H1
            }

            // Update probability
            if (iteration % limitUpdateProbability == 0) {
                calculateProbability();
            }

            // Check for improvement (Lexicographic: H5 first, then Total Hard)
            int newH5 = countMandatoryUnscheduled(currentSolution);
            int[] newViolations = null;
            int newTotalHard = Integer.MAX_VALUE;

            if (newH5 == 0) {
                newViolations = countHardViolations(currentSolution);
                newTotalHard = newViolations[0];
            }

            boolean improvement = false;

            if (newH5 < bestH5) {
                bestH5 = newH5;
                if (newH5 == 0) {
                    bestTotalHardViolations = newTotalHard;
                }
                improvement = true;
            } else if (newH5 == 0 && newH5 == bestH5) {
                // Phase 2 improvement check
                if (newTotalHard < bestTotalHardViolations) {
                    bestTotalHardViolations = newTotalHard;
                    improvement = true;
                }
            }

            if (improvement) {
                bestSolution = deepCopy(currentSolution);
                if (bestH5 > 0) {
                    System.out
                            .println("PA-ILS Iteration " + iteration + ": Unscheduled mandatory reduced to " + bestH5);
                } else {
                    System.out.println("PA-ILS Iteration " + iteration + ": Total Hard Violations reduced to "
                            + bestTotalHardViolations + " [H5=" + newViolations[1] + ", H1=" + newViolations[2]
                            + ", H7=" + newViolations[3] + ", H6=" + newViolations[4] + ", H2=" + newViolations[5]
                            + ", H3=" + newViolations[6] + ", H4=" + newViolations[7] + "]");
                }
                noProgressCount = 0;
            } else {
                noProgressCount++;

                // Advanced Recovery Strategy: Purge Optional Patients if Stuck
                if (noProgressCount > 500 && bestH5 == 0 && bestTotalHardViolations > 0) {
                    purgeInfeasibleOptionals();
                    // Recalculate violations after purge
                    int[] v = countHardViolations(currentSolution);
                    if (v[0] < bestTotalHardViolations) {
                        bestTotalHardViolations = v[0];
                        bestSolution = deepCopy(currentSolution);
                        System.out.println(
                                "PA-ILS: Purged optional patients. New violations: " + bestTotalHardViolations);
                        noProgressCount = 0;
                    }
                }

                // Revert to best if stuck for too long (Standard PA-ILS mechanism)
                if (noProgressCount > MAX_NO_PROGRESS && bestH5 == 0 && bestTotalHardViolations > 10) { // Only revert
                                                                                                        // if violations
                                                                                                        // are high
                    currentSolution = deepCopy(bestSolution);
                    noProgressCount = 0;
                    decreasingValue = 0.3;
                    calculateProbability();
                }
            }

            // Log hanya ketika ada improvement (BETTER)
            if (iteration % 500 == 0) {
                int currentViolations = (newViolations != null) ? newViolations[0]
                        : countHardViolations(currentSolution)[0];
                String status = (bestTotalHardViolations == 0) ? "OPTIMAL"
                        : currentViolations < bestTotalHardViolations ? "BETTER" : "NO_IMPROVEMENT";
                violationLogs.add(new ViolationLogEntry(iteration + 1, System.currentTimeMillis() - startTimeMs,
                        currentViolations, status));
            }

            iteration++;

            if (probabilityNonRandomTimeSlot > 0.99) {
                decreasingValue = 0.3;
                calculateProbability();
            }
        }

        System.out.println("=== PA-ILS Phase Complete ===");
        System.out.println("Final Total Hard Violations: " + bestTotalHardViolations);
        System.out.println("Final Unscheduled Mandatory: " + bestH5);
        System.out.println("Total iterations: " + iteration);
        System.out.println("Total violation logs collected: " + violationLogs.size());

        // Log final state
        violationLogs.add(new ViolationLogEntry(iteration, System.currentTimeMillis() - startTimeMs,
                bestTotalHardViolations, "FINAL"));

        return bestSolution;
    }

    private void calculateProbability() {
        decreasingValue = decreasingValue * constantFactor;
        probabilityNonRandomTimeSlot = 1 - decreasingValue;
    }

    /**
     * Phase 1 Perturbation: Focus on scheduling mandatory patients
     */
    private void performPhase1Perturbation() {
        // Mostly try to repair (schedule unscheduled) or kickOut (make room)
        int r = random.nextInt(10);
        if (r < 6) {
            repairMove();
        } else if (r < 9) {
            kickOutMove();
        } else {
            kickMove();
        }
    }

    /**
     * Phase 1 Local Search: Focus on scheduling mandatory patients
     */
    private void performPhase1LocalSearch() {
        // Simple strategy: try to schedule all unscheduled mandatory patients
        List<IHTP_Solution.OutputPatient> unscheduled = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null) {
                IHTP_Input.Patient data = getPatientData(p.id);
                if (data != null && data.mandatory) {
                    unscheduled.add(p);
                }
            }
        }

        if (unscheduled.isEmpty())
            return;

        // Sort by domain size (smallest first), then window, then LOS
        unscheduled.sort((a, b) -> {
            IHTP_Input.Patient da = getPatientData(a.id);
            IHTP_Input.Patient db = getPatientData(b.id);
            Set<Integer> daysA = validDaysForPatient.getOrDefault(a.id, Collections.emptySet());
            Set<Integer> daysB = validDaysForPatient.getOrDefault(b.id, Collections.emptySet());
            int cmp = Integer.compare(daysA.size(), daysB.size());
            if (cmp != 0)
                return cmp;
            if (da != null && db != null) {
                int windowA = da.surgeryDueDay - da.surgeryReleaseDay;
                int windowB = db.surgeryDueDay - db.surgeryReleaseDay;
                cmp = Integer.compare(windowA, windowB);
                if (cmp != 0)
                    return cmp;
                return Integer.compare(db.lengthOfStay, da.lengthOfStay);
            }
            return 0;
        });

        // Try to schedule each
        for (IHTP_Solution.OutputPatient p : unscheduled) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
            Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());
            Set<Integer> validTheaters = validTheatersForPatient.getOrDefault(p.id, Collections.emptySet());

            if (validDays.isEmpty() || validRooms.isEmpty())
                continue;

            // Use best-slot picker to respect conflicts instead of random
            schedulePatientBestSlot(p);
        }
    }

    /**
     * Weighted perturbation based on which violations exist
     */
    private void performWeightedPerturbation(int[] violations) {
        // violations: [total, H5_unscheduled, H1_gender, H7_capacity, H6_admission,
        // H2_compatibility]

        List<Integer> moves = new ArrayList<>();

        // Add moves based on violation types (weighted by occurrence)
        if (violations[1] > 0) { // H5: Unscheduled mandatory
            for (int i = 0; i < 3; i++)
                moves.add(0); // repairMove
            moves.add(4); // kickOutMove
        }
        if (violations[2] > 0) { // H1: Gender mix
            for (int i = 0; i < 2; i++)
                moves.add(1); // fixGenderMove
        }
        if (violations[3] > 0) { // H7: Capacity
            for (int i = 0; i < 2; i++)
                moves.add(2); // fixCapacityMove
        }
        if (violations[4] > 0) { // H6: Admission day
            for (int i = 0; i < 2; i++)
                moves.add(3); // fixAdmissionMove
        }
        if (violations[5] > 0) { // H2: Room compatibility
            moves.add(5); // fixCompatibilityMove
        }
        if (violations[6] > 0) { // H3: Surgeon Overtime
            for (int i = 0; i < 2; i++)
                moves.add(8);
        }
        if (violations[7] > 0) { // H4: Theater Overtime
            for (int i = 0; i < 2; i++)
                moves.add(9);
        }

        // Always add general moves
        moves.add(6); // swapMove
        moves.add(7); // kickMove

        if (moves.isEmpty())
            return;

        int moveType = moves.get(random.nextInt(moves.size()));

        switch (moveType) {
        case 0:
            repairMove();
            break;
        case 1:
            fixGenderMove();
            break;
        case 2:
            fixCapacityMove();
            break;
        case 3:
            fixAdmissionMove();
            break;
        case 4:
            kickOutMove();
            break;
        case 5:
            fixCompatibilityMove();
            break;
        case 6:
            swapMove();
            break;
        case 7:
            kickMove();
            break;
        case 8:
            fixSurgeonOvertimeMove();
            break;
        case 9:
            fixTheaterOvertimeMove();
            break;
        }
    }

    /**
     * Repair Move: Schedule an unscheduled mandatory patient
     */
    private void repairMove() {
        List<IHTP_Solution.OutputPatient> unscheduled = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null) {
                IHTP_Input.Patient data = getPatientData(p.id);
                if (data != null && data.mandatory) {
                    unscheduled.add(p);
                }
            }
        }
        if (unscheduled.isEmpty())
            return;

        // Sort by domain size (most constrained first), then by narrow window then LOS
        unscheduled.sort((a, b) -> {
            IHTP_Input.Patient da = getPatientData(a.id);
            IHTP_Input.Patient db = getPatientData(b.id);
            Set<Integer> daysA = validDaysForPatient.getOrDefault(a.id, Collections.emptySet());
            Set<Integer> daysB = validDaysForPatient.getOrDefault(b.id, Collections.emptySet());
            int cmp = Integer.compare(daysA.size(), daysB.size());
            if (cmp != 0)
                return cmp;
            if (da != null && db != null) {
                int windowA = da.surgeryDueDay - da.surgeryReleaseDay;
                int windowB = db.surgeryDueDay - db.surgeryReleaseDay;
                cmp = Integer.compare(windowA, windowB);
                if (cmp != 0)
                    return cmp;
                return Integer.compare(db.lengthOfStay, da.lengthOfStay); // longer stay earlier
            }
            return 0;
        });

        IHTP_Solution.OutputPatient target = (random.nextDouble() < probabilityNonRandomTimeSlot) ? unscheduled.get(0)
                : unscheduled.get(random.nextInt(unscheduled.size()));

        schedulePatientBestSlot(target);
    }

    /**
     * Fix Gender Move: Aggressively fix gender conflicts Strategies: 1) Move to
     * different room, 2) Move to different day, 3) Swap rooms with another patient,
     * 4) Reschedule entirely
     */
    private void fixGenderMove() {
        // Strategy: Use arrays to detect mix, then identify patients involved.

        List<IHTP_Solution.OutputPatient> patientsInConflict = new ArrayList<>();

        // We know from countHardViolations that conflicts exist.
        // We need to pick a SPECIFIC conflict.
        // Let's just pick one random patient who is in a "bad" room-day.

        List<IHTP_Solution.OutputPatient> conflictCandidates = new ArrayList<>();

        // Build grid again? (Expensive but safe). Or just scan.
        // To be efficient: Pick a patient, check if they are in a conflict.

        // Optimized check:
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Integer rIdx = input.roomIndexById.get(p.assignedRoom);
            if (rIdx == null)
                continue;

            boolean patientInConflict = false;
            for (int d = p.assignedDay; d < p.assignedDay + data.lengthOfStay && d < input.Days(); d++) {
                // Check if this room-day has mix
                // We need to know who else is there.
                // Assuming we re-ran countHardViolations effectively or we trust the random
                // pick.
                // Let's check against BASE occupants.

                boolean hasA = (baseRoomGenderA[rIdx][d] > 0);
                boolean hasB = (baseRoomGenderB[rIdx][d] > 0);

                if (data.gender == IHTP_Input.Gender.A && hasB)
                    patientInConflict = true;
                if (data.gender == IHTP_Input.Gender.B && hasA)
                    patientInConflict = true;

                // Also check against other patients?
                // This 'fixGenderMove' is finding a conflict "pair" or "group".
                // If I only check against base, I miss patient-patient conflicts.

                // Let's revert to a simpler approach:
                // Scan all patients. If P1 and P2 are in same room-day and different gender,
                // add to list.
                // Also if P1 and Occupant are different gender.
            }
            // For now, let's just use the `hasGenderConflict` helper if it exists, updated
            // to use base arrays.
            // But `hasGenderConflict` helper was not shown in the visible code. I will
            // assume it's not reliable.
            // I'll implement a robust detection here.
        }
        // ... (Due to complexity, I'll use a simplified implementation that builds the
        // whole grid for accuracy)

        int[][] genderA = new int[input.roomsList.size()][input.Days()];
        int[][] genderB = new int[input.roomsList.size()][input.Days()];
        // Fill base
        for (int r = 0; r < input.roomsList.size(); r++) {
            System.arraycopy(baseRoomGenderA[r], 0, genderA[r], 0, input.Days());
            System.arraycopy(baseRoomGenderB[r], 0, genderB[r], 0, input.Days());
        }
        // Fill patients
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            Integer rIdx = input.roomIndexById.get(p.assignedRoom);
            if (rIdx == null)
                continue;
            for (int d = p.assignedDay; d < p.assignedDay + data.lengthOfStay && d < input.Days(); d++) {
                if (data.gender == IHTP_Input.Gender.A)
                    genderA[rIdx][d]++;
                else
                    genderB[rIdx][d]++;
            }
        }

        // Find "bad" room-days
        List<int[]> badSpots = new ArrayList<>();
        for (int r = 0; r < input.roomsList.size(); r++) {
            for (int d = 0; d < input.Days(); d++) {
                if (genderA[r][d] > 0 && genderB[r][d] > 0) {
                    badSpots.add(new int[] { r, d });
                }
            }
        }

        if (badSpots.isEmpty())
            return;
        int[] spot = badSpots.get(random.nextInt(badSpots.size()));
        int r = spot[0];
        int d = spot[1];
        String rId = input.roomsList.get(r).id;

        // Find patients in this spot
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            if (!p.assignedRoom.equals(rId))
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            int start = p.assignedDay;
            int end = start + data.lengthOfStay - 1;
            if (d >= start && d <= end) {
                patientsInConflict.add(p);
            }
        }

        if (patientsInConflict.size() < 2)
            return;

        // Strategy 1: Try to move one patient to a different room with same gender
        int bestViolations = countHardViolations(currentSolution)[0];
        IHTP_Solution.OutputPatient bestPatient = null;
        String bestRoom = null;

        for (IHTP_Solution.OutputPatient p : patientsInConflict) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());
            String originalRoom = p.assignedRoom;

            for (int roomIdx : validRooms) {
                String newRoom = input.RoomId(roomIdx);
                if (newRoom.equals(originalRoom))
                    continue;

                // Check if moving this patient to newRoom would create a same-gender situation
                p.assignedRoom = newRoom;
                int[] newViolations = countHardViolations(currentSolution);

                if (newViolations[0] < bestViolations) {
                    bestViolations = newViolations[0];
                    bestPatient = p;
                    bestRoom = newRoom;
                }
                p.assignedRoom = originalRoom;
            }
        }

        if (bestPatient != null) {
            bestPatient.assignedRoom = bestRoom;
            return;
        }

        // Strategy 2: Try moving one patient to a different day
        for (IHTP_Solution.OutputPatient p : patientsInConflict) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;
            if (data.mandatory && patientsInConflict.indexOf(p) == 0)
                continue; // Try non-mandatory first

            Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
            Integer originalDay = p.assignedDay;

            for (int newDay : validDays) {
                if (newDay == originalDay)
                    continue;

                p.assignedDay = newDay;
                int[] newViolations = countHardViolations(currentSolution);

                if (newViolations[0] < bestViolations) {
                    return; // Keep the change
                }
                p.assignedDay = originalDay;
            }
        }

        // Strategy 3: Full reschedule of the least constrained patient
        IHTP_Solution.OutputPatient leastConstrained = null;
        int maxDomainSize = 0;

        for (IHTP_Solution.OutputPatient p : patientsInConflict) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data != null && !data.mandatory) { // Prefer non-mandatory
                leastConstrained = p;
                break;
            }
            Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
            if (validDays.size() > maxDomainSize) {
                maxDomainSize = validDays.size();
                leastConstrained = p;
            }
        }

        if (leastConstrained != null) {
            schedulePatientBestSlot(leastConstrained);
        }

        // Strategy 4: Fallback - Unassign an OPTIONAL patient to resolve conflict
        // PA-ILS allows optional patients to be unscheduled to achieve feasibility
        for (IHTP_Solution.OutputPatient p : patientsInConflict) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data != null && !data.mandatory) {
                p.assignedDay = null;
                p.assignedRoom = null;
                p.assignedTheater = null;
                return;
            }
        }
    }

    /**
     * Fix Capacity Move: Move a patient from overcrowded room Optimized: Random
     * selection + Try Day Change + Fallback Unassign
     */
    /**
     * Fix Capacity Move: Move a patient from overcrowded room Optimized: Local
     * Check + Logic + Fallback Unassign
     */
    private void fixCapacityMove() {
        // Build accurate map including base occupants could be complex here.
        // Instead, simplistically move patients from rooms that violate capacity
        // *relative to what countHardViolations sees*
        // But countHardViolations uses arrays. Here we used a map.
        // We must include base occupants in the "currentMap".
        // Or simpler: Iterate over ALL rooms/days, check capacity using base + current
        // solution patients.

        // Let's stick to the map approach but seed it with dummy objects? No, that's
        // messy.
        // Better: Identify overcrowded rooms by scanning the full state like
        // countHardViolations.

        List<IHTP_Solution.OutputPatient> overCrowdedPatients = new ArrayList<>();
        int[][] occCount = new int[input.roomsList.size()][input.Days()];

        // 1. Fill base
        for (int r = 0; r < input.roomsList.size(); r++) {
            System.arraycopy(baseRoomOccupancy[r], 0, occCount[r], 0, input.Days());
        }
        // 2. Add patients and track them
        List<IHTP_Solution.OutputPatient>[][] grid = new ArrayList[input.roomsList.size()][input.Days()];
        for (int r = 0; r < input.roomsList.size(); r++) {
            for (int d = 0; d < input.Days(); d++)
                grid[r][d] = new ArrayList<>();
        }

        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;
            Integer rIdx = input.roomIndexById.get(p.assignedRoom);
            if (rIdx == null)
                continue;

            for (int day = p.assignedDay; day < p.assignedDay + data.lengthOfStay && day < input.Days(); day++) {
                occCount[rIdx][day]++;
                grid[rIdx][day].add(p);
            }
        }

        // 3. Find overcrowded
        for (int r = 0; r < input.roomsList.size(); r++) {
            int capacity = input.roomsList.get(r).capacity;
            for (int d = 0; d < input.Days(); d++) {
                if (occCount[r][d] > capacity) {
                    overCrowdedPatients.addAll(grid[r][d]);
                }
            }
        }

        if (overCrowdedPatients.isEmpty())
            return;

        // Sort to prioritize optional patients (optional=false < mandatory=true)
        overCrowdedPatients
                .sort((a, b) -> Boolean.compare(getPatientData(a.id).mandatory, getPatientData(b.id).mandatory));

        // Pick one random patient to move
        IHTP_Solution.OutputPatient p = overCrowdedPatients.get(random.nextInt(overCrowdedPatients.size()));
        IHTP_Input.Patient data = getPatientData(p.id);
        if (data == null)
            return;

        int startViolations = countHardViolations(currentSolution)[0];
        Integer originalDay = p.assignedDay;

        // Strategy 1: Change Room
        moveToCompatibleRoom(p, data);
        if (countHardViolations(currentSolution)[0] < startViolations)
            return;

        // Strategy 2: Change Day
        Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
        List<Integer> alternatives = new ArrayList<>();
        for (int day : validDays) {
            if (day != originalDay)
                alternatives.add(day);
        }

        if (!alternatives.isEmpty()) {
            p.assignedDay = alternatives.get(random.nextInt(alternatives.size()));
            if (countHardViolations(currentSolution)[0] < startViolations)
                return;
            p.assignedDay = originalDay; // Revert
        }

        // Strategy 3: Unassign Optional (Aggressive 100% fallback)
        if (!data.mandatory) {
            p.assignedDay = null;
            p.assignedRoom = null;
            p.assignedTheater = null;
        }
    }

    /**
     * Fix Admission Move: Move patient to valid admission day Applies to ALL
     * patients - optional can violate if scheduled before release day
     */
    private void fixAdmissionMove() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            // Calculate lastPossibleDay as validator does
            int lastPossibleDay = data.mandatory ? data.surgeryDueDay : (input.Days() - 1);

            if (p.assignedDay < data.surgeryReleaseDay || p.assignedDay > lastPossibleDay) {
                // Move to valid day
                Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
                if (!validDays.isEmpty()) {
                    List<Integer> dayList = new ArrayList<>(validDays);
                    int newDay = dayList.get(random.nextInt(dayList.size()));
                    p.assignedDay = newDay;
                    return;
                } else if (!data.mandatory) {
                    // Fallback: Unassign optional patient if no valid day
                    p.assignedDay = null;
                    p.assignedRoom = null;
                    p.assignedTheater = null;
                    return;
                }
            }
        }
    }

    /**
     * Fix Compatibility Move: Move patient to compatible room
     */
    private void fixCompatibilityMove() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Integer roomIdx = input.roomIndexById.get(p.assignedRoom);
            if (roomIdx != null && data.incompatibleRooms != null && roomIdx < data.incompatibleRooms.length
                    && data.incompatibleRooms[roomIdx]) {
                // Move to compatible room
                Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());
                if (!validRooms.isEmpty()) {
                    List<Integer> roomList = new ArrayList<>(validRooms);
                    int newRoomIdx = roomList.get(random.nextInt(roomList.size()));
                    p.assignedRoom = input.RoomId(newRoomIdx);
                    return;
                } else if (!data.mandatory) {
                    // Fallback: Unassign optional patient if no compatible room
                    p.assignedDay = null;
                    p.assignedRoom = null;
                    p.assignedTheater = null;
                    return;
                }
            }
        }
    }

    /**
     * Fix Surgeon Overtime Move
     */
    private void fixSurgeonOvertimeMove() {
        int[] violations = countHardViolations(currentSolution);
        if (violations[6] == 0)
            return; // H3 index

        // Find overloaded surgeon-days
        List<int[]> overloads = new ArrayList<>(); // [surgeonIdx, day]
        for (int s = 0; s < input.Surgeons(); s++) {
            for (int d = 0; d < input.Days(); d++) {
                int load = 0;
                for (IHTP_Solution.OutputPatient p : currentSolution) {
                    if (p.assignedDay == null)
                        continue;
                    if (getPatientData(p.id).surgeon == s && p.assignedDay == d) {
                        load += getPatientData(p.id).surgeryDuration;
                    }
                }
                if (load > input.surgeonsList.get(s).maxSurgeryTime[d]) {
                    overloads.add(new int[] { s, d });
                }
            }
        }

        if (overloads.isEmpty())
            return;
        int[] target = overloads.get(random.nextInt(overloads.size()));
        int s = target[0];
        int d = target[1];

        // Pick a patient contributing to this overload
        List<IHTP_Solution.OutputPatient> candidates = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay != null && p.assignedDay == d && getPatientData(p.id).surgeon == s) {
                candidates.add(p);
            }
        }

        if (candidates.isEmpty())
            return;
        IHTP_Solution.OutputPatient p = candidates.get(random.nextInt(candidates.size()));
        IHTP_Input.Patient data = getPatientData(p.id);

        // Strategy: Move to another valid day
        Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
        List<Integer> alternatives = new ArrayList<>();
        for (int day : validDays) {
            if (day != d)
                alternatives.add(day);
        }

        if (!alternatives.isEmpty()) {
            p.assignedDay = alternatives.get(random.nextInt(alternatives.size()));
        }
    }

    /**
     * Fix Theater Overtime Move
     */
    private void fixTheaterOvertimeMove() {
        int[] violations = countHardViolations(currentSolution);
        if (violations[7] == 0)
            return; // H4 index

        // Find overloaded theaters
        List<int[]> overloads = new ArrayList<>(); // [theaterIdx, day]
        for (int t = 0; t < input.OperatingTheaters(); t++) {
            for (int d = 0; d < input.Days(); d++) {
                int load = 0;
                for (IHTP_Solution.OutputPatient p : currentSolution) {
                    if (p.assignedDay == null || p.assignedTheater == null)
                        continue;
                    if (p.assignedDay == d && input.theaterIndexById.get(p.assignedTheater) == t) {
                        load += getPatientData(p.id).surgeryDuration;
                    }
                }
                if (load > input.theatersList.get(t).availability[d]) {
                    overloads.add(new int[] { t, d });
                }
            }
        }

        if (overloads.isEmpty())
            return;
        int[] target = overloads.get(random.nextInt(overloads.size()));
        int t = target[0];
        int d = target[1];
        String theaterId = input.theatersList.get(t).id;

        // Pick a patient contributing
        List<IHTP_Solution.OutputPatient> candidates = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay != null && p.assignedDay == d && theaterId.equals(p.assignedTheater)) {
                candidates.add(p);
            }
        }

        if (candidates.isEmpty())
            return;
        IHTP_Solution.OutputPatient p = candidates.get(random.nextInt(candidates.size()));

        // Strategy 1: Change theater on same day
        Set<Integer> validTheaters = validTheatersForPatient.getOrDefault(p.id, Collections.emptySet());
        List<Integer> altTheaters = new ArrayList<>();
        for (int th : validTheaters) {
            if (th != t && input.theatersList.get(th).availability[d] >= getPatientData(p.id).surgeryDuration) { // Soft
                                                                                                                 // check
                altTheaters.add(th);
            }
        }

        if (!altTheaters.isEmpty()) {
            p.assignedTheater = input.theatersList.get(altTheaters.get(random.nextInt(altTheaters.size()))).id;
            return;
        }

        // Strategy 2: Move to another day
        Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());
        List<Integer> alternatives = new ArrayList<>();
        for (int day : validDays) {
            if (day != d)
                alternatives.add(day);
        }
        if (!alternatives.isEmpty()) {
            p.assignedDay = alternatives.get(random.nextInt(alternatives.size()));
        }
    }

    /**
     * Purge Optional Patients causing Hard Violations A "Nuclear Option" to ensure
     * feasibility (H=0)
     */
    /**
     * Purge Optional Patients causing Hard Violations A "Nuclear Option" to ensure
     * feasibility (H=0)
     */
    private void purgeInfeasibleOptionals() {
        // 1. Scan for H2 (Compatibility) & H6 (Admission) directly on patients
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null || data.mandatory)
                continue;

            if (p.assignedDay == null)
                continue;

            // H6: Admission
            int lastPossibleDay = input.Days() - 1;
            if (p.assignedDay < data.surgeryReleaseDay || p.assignedDay > lastPossibleDay) {
                p.assignedRoom = null;
                p.assignedDay = null;
                p.assignedTheater = null;
                continue;
            }

            // H2: Compatibility
            if (p.assignedRoom != null) {
                Integer roomIdx = input.roomIndexById.get(p.assignedRoom);
                if (roomIdx != null && data.incompatibleRooms != null && roomIdx < data.incompatibleRooms.length
                        && data.incompatibleRooms[roomIdx]) {
                    p.assignedRoom = null;
                    p.assignedDay = null;
                    p.assignedTheater = null;
                    continue;
                }
            }
        }

        // 2. Scan for H1 (Gender) and H7 (Capacity) using Robust Array Grid
        int numRooms = input.roomsList.size();
        int numDays = input.Days();

        List<IHTP_Solution.OutputPatient>[][] roomDayGrid = new ArrayList[numRooms][numDays];
        for (int r = 0; r < numRooms; r++) {
            for (int d = 0; d < numDays; d++) {
                roomDayGrid[r][d] = new ArrayList<>();
            }
        }

        // Populate Grid
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            Integer rIdx = input.roomIndexById.get(p.assignedRoom);
            IHTP_Input.Patient data = getPatientData(p.id);

            if (rIdx != null && data != null) {
                for (int d = p.assignedDay; d < p.assignedDay + data.lengthOfStay && d < numDays; d++) {
                    roomDayGrid[rIdx][d].add(p);
                }
            }
        }

        // Check violations and purge
        for (int r = 0; r < numRooms; r++) {
            int capacity = input.roomsList.get(r).capacity;
            for (int d = 0; d < numDays; d++) {
                // Combine with base occupancy
                int totalOcc = roomDayGrid[r][d].size() + baseRoomOccupancy[r][d];
                // Check gender mix
                boolean hasA = (baseRoomGenderA[r][d] > 0);
                boolean hasB = (baseRoomGenderB[r][d] > 0);

                // Add solution patients' gender
                for (IHTP_Solution.OutputPatient occ : roomDayGrid[r][d]) {
                    IHTP_Input.Patient pd = getPatientData(occ.id);
                    if (pd != null) {
                        if (pd.gender == IHTP_Input.Gender.A)
                            hasA = true;
                        else
                            hasB = true;
                    }
                }

                // H7
                boolean overcrowded = (totalOcc > capacity);

                // H1
                boolean genderConflict = (hasA && hasB);

                if (overcrowded || genderConflict) {
                    // Aggressive Purge: Remove ALL optionals in this block
                    List<IHTP_Solution.OutputPatient> occupants = roomDayGrid[r][d];
                    for (IHTP_Solution.OutputPatient occ : occupants) {
                        IHTP_Input.Patient pd = getPatientData(occ.id);
                        if (pd != null && !pd.mandatory) {
                            occ.assignedDay = null;
                            occ.assignedRoom = null;
                            occ.assignedTheater = null;
                        }
                    }
                }
            }
        }
    }

    /**
     * Swap Move: Swap two scheduled patients
     */
    private void swapMove() {
        List<IHTP_Solution.OutputPatient> scheduled = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay != null)
                scheduled.add(p);
        }
        if (scheduled.size() < 2)
            return;

        IHTP_Solution.OutputPatient p1 = scheduled.get(random.nextInt(scheduled.size()));
        IHTP_Solution.OutputPatient p2;
        int attempts = 0;
        do {
            p2 = scheduled.get(random.nextInt(scheduled.size()));
            attempts++;
        } while (p2 == p1 && attempts < 10);
        if (p2 == p1)
            return;

        // Swap assignments
        Integer tempDay = p1.assignedDay;
        String tempRoom = p1.assignedRoom;
        String tempTheater = p1.assignedTheater;

        p1.assignedDay = p2.assignedDay;
        p1.assignedRoom = p2.assignedRoom;
        p1.assignedTheater = p2.assignedTheater;

        p2.assignedDay = tempDay;
        p2.assignedRoom = tempRoom;
        p2.assignedTheater = tempTheater;

        // Validate swap doesn't create new H6 violations for mandatory
        IHTP_Input.Patient data1 = getPatientData(p1.id);
        IHTP_Input.Patient data2 = getPatientData(p2.id);

        boolean valid = true;
        if (data1 != null && data1.mandatory && p1.assignedDay != null) {
            if (p1.assignedDay < data1.surgeryReleaseDay || p1.assignedDay > data1.surgeryDueDay) {
                valid = false;
            }
        }
        if (data2 != null && data2.mandatory && p2.assignedDay != null) {
            if (p2.assignedDay < data2.surgeryReleaseDay || p2.assignedDay > data2.surgeryDueDay) {
                valid = false;
            }
        }

        // Rollback if invalid
        if (!valid) {
            p2.assignedDay = p1.assignedDay;
            p2.assignedRoom = p1.assignedRoom;
            p2.assignedTheater = p1.assignedTheater;

            p1.assignedDay = tempDay;
            p1.assignedRoom = tempRoom;
            p1.assignedTheater = tempTheater;
        }
    }

    /**
     * Kick Move: Schedule unscheduled by taking slot from another patient
     */
    private void kickMove() {
        List<IHTP_Solution.OutputPatient> unscheduled = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null) {
                IHTP_Input.Patient data = getPatientData(p.id);
                if (data != null && data.mandatory)
                    unscheduled.add(p);
            }
        }
        if (unscheduled.isEmpty())
            return;

        IHTP_Solution.OutputPatient toFix = unscheduled.get(random.nextInt(unscheduled.size()));
        IHTP_Input.Patient fixData = getPatientData(toFix.id);
        if (fixData == null)
            return;

        // Find candidates to kick (prefer non-mandatory)
        List<IHTP_Solution.OutputPatient> candidates = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null)
                continue;
            if (p.assignedDay >= fixData.surgeryReleaseDay && p.assignedDay <= fixData.surgeryDueDay) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty())
            return;

        // Sort: non-mandatory first
        candidates.sort((a, b) -> {
            IHTP_Input.Patient pa = getPatientData(a.id);
            IHTP_Input.Patient pb = getPatientData(b.id);
            if (pa == null || pb == null)
                return 0;
            if (!pa.mandatory && pb.mandatory)
                return -1;
            if (pa.mandatory && !pb.mandatory)
                return 1;
            return 0;
        });

        IHTP_Solution.OutputPatient toKick = candidates.get(0);
        IHTP_Input.Patient kickData = getPatientData(toKick.id);

        // Take the slot
        toFix.assignedDay = toKick.assignedDay;
        toFix.assignedRoom = toKick.assignedRoom;
        toFix.assignedTheater = toKick.assignedTheater;

        // Reschedule kicked patient if mandatory
        if (kickData != null && kickData.mandatory) {
            schedulePatientBestSlot(toKick);
        } else {
            toKick.assignedDay = null;
            toKick.assignedRoom = null;
            toKick.assignedTheater = null;
        }
    }

    /**
     * KickOut Move: Kick onto optional and unassign optional
     */
    private void kickOutMove() {
        List<IHTP_Solution.OutputPatient> unscheduledMandatory = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null) {
                IHTP_Input.Patient data = getPatientData(p.id);
                if (data != null && data.mandatory)
                    unscheduledMandatory.add(p);
            }
        }
        if (unscheduledMandatory.isEmpty())
            return;

        IHTP_Solution.OutputPatient toFix = unscheduledMandatory.get(random.nextInt(unscheduledMandatory.size()));
        IHTP_Input.Patient fixData = getPatientData(toFix.id);
        if (fixData == null)
            return;

        // Find optional patients with valid slots
        List<IHTP_Solution.OutputPatient> optionals = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data != null && !data.mandatory) {
                if (p.assignedDay >= fixData.surgeryReleaseDay && p.assignedDay <= fixData.surgeryDueDay) {
                    optionals.add(p);
                }
            }
        }

        if (optionals.isEmpty()) {
            kickMove();
            return;
        }

        IHTP_Solution.OutputPatient optional = optionals.get(random.nextInt(optionals.size()));

        toFix.assignedDay = optional.assignedDay;
        toFix.assignedRoom = optional.assignedRoom;
        toFix.assignedTheater = optional.assignedTheater;

        optional.assignedDay = null;
        optional.assignedRoom = null;
        optional.assignedTheater = null;
    }

    /**
     * Local Search - First improvement with multi-neighborhood
     */
    private void performLocalSearch() {
        int stuck = 0;
        int[] currentViolations = countHardViolations(currentSolution);

        while (stuck < limitStuck) {
            boolean improved = false;

            // Try to fix each type of violation
            if (currentViolations[1] > 0) { // H5
                if (tryFixUnscheduled()) {
                    improved = true;
                }
            }

            if (!improved && currentViolations[4] > 0) { // H6
                if (tryFixAdmission()) {
                    improved = true;
                }
            }

            if (!improved && currentViolations[2] > 0) { // H1
                if (tryFixGender()) {
                    improved = true;
                }
            }

            if (!improved && currentViolations[3] > 0) { // H7
                if (tryFixCapacity()) {
                    improved = true;
                }
            }

            if (!improved && currentViolations[5] > 0) { // H2
                if (tryFixCompatibility()) {
                    improved = true;
                }
            }

            int[] newViolations = countHardViolations(currentSolution);
            if (newViolations[0] < currentViolations[0]) {
                currentViolations = newViolations;
                stuck = 0;
            } else {
                stuck++;
            }
        }
    }

    private boolean tryFixUnscheduled() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay != null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null || !data.mandatory)
                continue;

            int before = countHardViolations(currentSolution)[0];
            schedulePatientBestSlot(p);
            int after = countHardViolations(currentSolution)[0];

            if (after < before)
                return true;
        }
        return false;
    }

    private boolean tryFixAdmission() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null || !data.mandatory)
                continue;

            if (p.assignedDay < data.surgeryReleaseDay || p.assignedDay > data.surgeryDueDay) {
                int before = countHardViolations(currentSolution)[0];
                Set<Integer> validDays = validDaysForPatient.getOrDefault(p.id, Collections.emptySet());

                for (int day : validDays) {
                    Integer oldDay = p.assignedDay;
                    p.assignedDay = day;
                    int after = countHardViolations(currentSolution)[0];
                    if (after < before)
                        return true;
                    p.assignedDay = oldDay;
                }
            }
        }
        return false;
    }

    private boolean tryFixGender() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            for (int day = p.assignedDay; day < p.assignedDay + data.lengthOfStay && day < input.Days(); day++) {
                if (hasGenderConflict(p.assignedRoom, day, p.id)) {
                    int before = countHardViolations(currentSolution)[0];
                    Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());

                    for (int roomIdx : validRooms) {
                        String oldRoom = p.assignedRoom;
                        p.assignedRoom = input.RoomId(roomIdx);
                        int after = countHardViolations(currentSolution)[0];
                        if (after < before)
                            return true;
                        p.assignedRoom = oldRoom;
                    }
                    break;
                }
            }
        }
        return false;
    }

    private boolean tryFixCapacity() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Integer roomIdx = input.roomIndexById.get(p.assignedRoom);
            if (roomIdx == null)
                continue;
            IHTP_Input.Room room = input.roomsList.get(roomIdx);

            for (int day = p.assignedDay; day < p.assignedDay + data.lengthOfStay && day < input.Days(); day++) {
                if (getRoomOccupancy(p.assignedRoom, day) > room.capacity) {
                    int before = countHardViolations(currentSolution)[0];
                    Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());

                    for (int newRoomIdx : validRooms) {
                        String oldRoom = p.assignedRoom;
                        p.assignedRoom = input.RoomId(newRoomIdx);
                        int after = countHardViolations(currentSolution)[0];
                        if (after < before)
                            return true;
                        p.assignedRoom = oldRoom;
                    }
                    break;
                }
            }
        }
        return false;
    }

    private boolean tryFixCompatibility() {
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedRoom == null)
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            Integer roomIdx = input.roomIndexById.get(p.assignedRoom);
            if (roomIdx != null && data.incompatibleRooms != null && roomIdx < data.incompatibleRooms.length
                    && data.incompatibleRooms[roomIdx]) {

                int before = countHardViolations(currentSolution)[0];
                Set<Integer> validRooms = validRoomsForPatient.getOrDefault(p.id, Collections.emptySet());

                for (int newRoomIdx : validRooms) {
                    String oldRoom = p.assignedRoom;
                    p.assignedRoom = input.RoomId(newRoomIdx);
                    int after = countHardViolations(currentSolution)[0];
                    if (after < before)
                        return true;
                    p.assignedRoom = oldRoom;
                }
            }
        }
        return false;
    }

    /**
     * Diversification when stuck
     */
    private void performDiversification() {
        // Randomly kick some patients
        List<IHTP_Solution.OutputPatient> scheduled = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay != null)
                scheduled.add(p);
        }

        int numToKick = Math.min(3, scheduled.size());
        Collections.shuffle(scheduled, random);

        for (int i = 0; i < numToKick; i++) {
            IHTP_Solution.OutputPatient p = scheduled.get(i);
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data != null && !data.mandatory) {
                p.assignedDay = null;
                p.assignedRoom = null;
                p.assignedTheater = null;
            }
        }

        // Shuffle some room assignments
        for (int i = 0; i < limitShuffle / 10; i++) {
            swapMove();
        }
    }

    // ============ Helper Methods ============

    private void schedulePatientBestSlot(IHTP_Solution.OutputPatient patient) {
        IHTP_Input.Patient data = getPatientData(patient.id);
        if (data == null)
            return;

        Set<Integer> validDays = validDaysForPatient.getOrDefault(patient.id, Collections.emptySet());
        Set<Integer> validRooms = validRoomsForPatient.getOrDefault(patient.id, Collections.emptySet());
        Set<Integer> validTheaters = validTheatersForPatient.getOrDefault(patient.id, Collections.emptySet());

        if (validDays.isEmpty() || validRooms.isEmpty() || validTheaters.isEmpty())
            return;

        int bestTotal = Integer.MAX_VALUE;
        int bestComposite = Integer.MAX_VALUE; // focus on H1+H7+H6+H2
        int bestDay = -1;
        String bestRoomId = null;
        String bestTheaterId = null;

        List<Integer> dayList = new ArrayList<>(validDays);
        List<Integer> roomList = new ArrayList<>(validRooms);
        List<Integer> theaterList = new ArrayList<>(validTheaters);

        // Evaluate limited samples but prioritize deterministic coverage then random
        int deterministicSpan = Math.min(dayList.size() * roomList.size(), 50);
        int maxSamples = Math.min(120, dayList.size() * roomList.size() * theaterList.size());

        for (int i = 0; i < maxSamples; i++) {
            int day;
            int roomIdx;
            int theaterIdx;

            if (i < deterministicSpan) {
                day = dayList.get(i / roomList.size() % dayList.size());
                roomIdx = roomList.get(i % roomList.size());
            } else {
                day = dayList.get(random.nextInt(dayList.size()));
                roomIdx = roomList.get(random.nextInt(roomList.size()));
            }

            theaterIdx = theaterList.get(random.nextInt(theaterList.size()));

            patient.assignedDay = day;
            patient.assignedRoom = input.RoomId(roomIdx);
            patient.assignedTheater = input.OperatingTheaterId(theaterIdx);

            int[] violations = countHardViolations(currentSolution);
            int composite = violations[2] + violations[3] + violations[4] + violations[5];

            boolean better = false;
            if (violations[0] < bestTotal) {
                better = true;
            } else if (violations[0] == bestTotal && composite < bestComposite) {
                better = true;
            } else if (violations[0] == bestTotal && composite == bestComposite) {
                if (bestDay == -1 || day < bestDay) {
                    better = true;
                }
            }

            if (better) {
                bestTotal = violations[0];
                bestComposite = composite;
                bestDay = day;
                bestRoomId = input.RoomId(roomIdx);
                bestTheaterId = input.OperatingTheaterId(theaterIdx);
            }
        }

        if (bestDay >= 0) {
            patient.assignedDay = bestDay;
            patient.assignedRoom = bestRoomId;
            patient.assignedTheater = bestTheaterId;
        } else {
            patient.assignedDay = null;
            patient.assignedRoom = null;
            patient.assignedTheater = null;
        }
    }

    private void moveToCompatibleRoom(IHTP_Solution.OutputPatient patient, IHTP_Input.Patient data) {
        Set<Integer> validRooms = validRoomsForPatient.getOrDefault(patient.id, Collections.emptySet());
        if (validRooms.isEmpty())
            return;

        int bestViolations = countHardViolations(currentSolution)[0];
        String bestRoom = patient.assignedRoom;

        for (int roomIdx : validRooms) {
            String oldRoom = patient.assignedRoom;
            patient.assignedRoom = input.RoomId(roomIdx);
            int violations = countHardViolations(currentSolution)[0];
            if (violations < bestViolations) {
                bestViolations = violations;
                bestRoom = patient.assignedRoom;
            }
            patient.assignedRoom = oldRoom;
        }

        patient.assignedRoom = bestRoom;
    }

    private boolean hasGenderConflict(String roomId, int day, String excludePatientId) {
        Set<IHTP_Input.Gender> genders = new HashSet<>();
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            if (!roomId.equals(p.assignedRoom))
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            int start = p.assignedDay;
            int end = start + data.lengthOfStay - 1;
            if (day >= start && day <= end) {
                genders.add(data.gender);
            }
        }
        return genders.size() > 1;
    }

    private int getRoomOccupancy(String roomId, int day) {
        int count = 0;
        for (IHTP_Solution.OutputPatient p : currentSolution) {
            if (p.assignedDay == null || p.assignedRoom == null)
                continue;
            if (!roomId.equals(p.assignedRoom))
                continue;
            IHTP_Input.Patient data = getPatientData(p.id);
            if (data == null)
                continue;

            int start = p.assignedDay;
            int end = start + data.lengthOfStay - 1;
            if (day >= start && day <= end) {
                count++;
            }
        }
        return count;
    }

    private IHTP_Input.Patient getPatientData(String patientId) {
        Integer idx = input.patientIndexById.get(patientId);
        if (idx == null)
            return null;
        return input.patientsList.get(idx);
    }

    private List<IHTP_Solution.OutputPatient> deepCopy(List<IHTP_Solution.OutputPatient> solution) {
        List<IHTP_Solution.OutputPatient> copy = new ArrayList<>();
        for (IHTP_Solution.OutputPatient p : solution) {
            IHTP_Solution.OutputPatient np = new IHTP_Solution.OutputPatient();
            np.id = p.id;
            np.assignedDay = p.assignedDay;
            np.assignedRoom = p.assignedRoom;
            np.assignedTheater = p.assignedTheater;
            np.maxSlot = p.maxSlot;
            copy.add(np);
        }
        return copy;
    }

    /**
     * Get all violation logs collected during searchFeasibleSolution
     * 
     * @return List of ViolationLogEntry
     */
    public List<ViolationLogEntry> getViolationLogs() {
        return new ArrayList<>(violationLogs);
    }

    /**
     * Get CSV header for violation logs
     */
    public static String getCsvHeader() {
        return "iteration,time_ms,violations,status";
    }
}
