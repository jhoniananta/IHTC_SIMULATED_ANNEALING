public class IHTP_Optimizer_HillClimbing {

  public static void main(String[] args) {
    if (args.length < 4) {
      System.out.println(
          "Usage: java -cp .;json-20250107.jar IHTP_Optimizer_HillClimbing <input_file> <time_limit_min> <log_csv> <output_json>");
      return;
    }

    try {
      String inputFile = args[0];
      int timeLimitMin = Integer.parseInt(args[1]);
      String logCsv = args[2];
      String outputJson = args[3];

      // Temporary file to bridge the two components
      String tempFeasibleSolution = "temp_feasible_hc_" + System.currentTimeMillis() + ".json";

      System.out.println("=================================================");
      System.out.println("   IHTP Optimizer: 2-Phase with Hill Climbing");
      System.out.println("=================================================");

      // PHASE 1: Find Feasible Solution (Hard Violations = 0) using IHTP_Solution
      // Logic
      System.out.println("\n[Phase 1] Constructing Feasible Solution...");
      boolean foundFeasible = IHTP_Solution.solve(inputFile, timeLimitMin, logCsv, tempFeasibleSolution);

      if (foundFeasible) {
        System.out.println("\n[Phase 1] SUCCESS: Feasible solution found.");
        System.out.println("          Transitioning to Hill Climbing Optimization.");

        // PHASE 2: Optimize Soft Constraints using IHTP_HillClimbing Logic
        System.out.println("\n[Phase 2] Running Hill Climbing...");
        IHTP_HillClimbing.runHC(inputFile, tempFeasibleSolution, timeLimitMin, logCsv, outputJson);

        System.out.println("\n[Phase 2] Optimization Complete.");
        System.out.println("Final Solution saved to: " + outputJson);

        // Cleanup
        new java.io.File(tempFeasibleSolution).delete();

      } else {
        System.err.println("\n[Phase 1] FAILED: Could not find a 0-violation solution within time limit.");
        System.err.println("          Hill Climbing phase skipped.");
        System.err.println("          Best effort solution saved to: " + tempFeasibleSolution);
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
