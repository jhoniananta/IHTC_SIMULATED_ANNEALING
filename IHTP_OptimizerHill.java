public class IHTP_OptimizerHill {

  public static void main(String[] args) {
    if (args.length < 4) {
      System.out.println(
          "Usage: java -cp .;json-20250107.jar IHTP_OptimizerHill <input_file> <time_limit_min> <log_csv> <output_json>");
      return;
    }

    try {
      String inputFile = args[0];
      int timeLimitMin = Integer.parseInt(args[1]);
      String logCsv = args[2];
      String outputJson = args[3];

      // Temporary file to bridge the two components
      String tempFeasibleSolution = "temp_feasible_" + System.currentTimeMillis() + ".json";

      System.out.println("=================================================");
      System.out.println("   IHTP Optimizer: Hill Climbing 2-Phase Solver");
      System.out.println("=================================================");
      System.out.println("Total Time Budget: " + timeLimitMin + " minutes");

      // Phase 1 gets full time budget - will stop when feasible solution found
      // Phase 2 gets remaining time after Phase 1 completes
      long totalStartTime = System.currentTimeMillis();
      long totalTimeMillis = timeLimitMin * 60L * 1000L;

      System.out.println("Phase 1: Feasibility Search (no time limit, full budget available)");
      System.out.println("Phase 2: Will use remaining time after Phase 1 completes");

      // PHASE 1: Find Feasible Solution (Hard Violations = 0) using IHTP_Solution
      // Logic
      System.out.println("\n[Phase 1] Constructing Feasible Solution...");
      long phase1Start = System.currentTimeMillis();
      boolean foundFeasible = IHTP_Solution.solve(inputFile, timeLimitMin, logCsv, tempFeasibleSolution);
      long phase1Duration = (System.currentTimeMillis() - phase1Start) / 1000;

      System.out.println("Phase 1 completed in " + phase1Duration + " seconds (" + (phase1Duration / 60.0) + " min)");

      if (foundFeasible) {
        System.out.println("\n[Phase 1] SUCCESS: Feasible solution found.");
        System.out.println("          Transitioning to Hill Climbing Optimization.");

        // PHASE 2: Optimize Soft Constraints using IHTP_HillClimb Logic
        // Calculate remaining time
        long elapsedMillis = System.currentTimeMillis() - totalStartTime;
        long remainingMillis = totalTimeMillis - elapsedMillis;
        int phase2Time = Math.max(1, (int) (remainingMillis / 1000 / 60)); // Convert to minutes, at least 1 min

        System.out.println("\n[Phase 2] Running Hill Climbing Optimization...");
        System.out.println("Phase 2 Time Available: " + phase2Time + " min");
        long phase2Start = System.currentTimeMillis();

        IHTP_HillClimb.runHillClimbingFromLauncher(inputFile, phase2Time, logCsv, outputJson, tempFeasibleSolution);

        long phase2Duration = (System.currentTimeMillis() - phase2Start) / 1000;
        System.out.println("Phase 2 completed in " + phase2Duration + " seconds");

        System.out.println("\n[Phase 2] Optimization Complete.");
        System.out.println("Final Solution saved to: " + outputJson);
        System.out.println("Total Time Used: " + ((phase1Duration + phase2Duration) / 60.0) + " minutes");

        // Cleanup
        new java.io.File(tempFeasibleSolution).delete();

      } else {
        System.err.println("\n[Phase 1] FAILED: Could not find a 0-violation solution within time limit.");
        System.err.println("          Hill Climbing phase skipped.");
        System.err.println("          Best effort solution saved to: " + tempFeasibleSolution);
        // Optionally move temp to output if wanted, but user asked for strictly
        // feasible first.
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}