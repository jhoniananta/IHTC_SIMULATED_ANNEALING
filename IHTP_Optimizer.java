
public class IHTP_Optimizer {

  public static void main(String[] args) {
    if (args.length < 4) {
      System.out.println(
          "Usage: java -cp .;json-20250107.jar IHTP_Optimizer <input_file> <time_limit_min> <log_csv> <output_json>");
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
      System.out.println("   IHTP Optimizer: Unified 2-Phase Solver");
      System.out.println("=================================================");

      // PHASE 1: Find Feasible Solution (Hard Violations = 0) using IHTP_Solution
      // Logic
      System.out.println("\n[Phase 1] Constructing Feasible Solution...");
      boolean foundFeasible = IHTP_Solution.solve(inputFile, timeLimitMin, logCsv, tempFeasibleSolution);

      if (foundFeasible) {
        System.out.println("\n[Phase 1] SUCCESS: Feasible solution found.");
        System.out.println("          Transitioning to Simulated Annealing Optimization.");

        // PHASE 2: Optimize Soft Constraints using IHTP_SA Logic
        System.out.println("\n[Phase 2] Running Simulated Annealing...");
        // Note: We give SA the full time allowance (or you could subtract consumed
        // time)
        // User requested "running params same", so we pass the time limit setting.
        IHTP_SA.runSA(inputFile, tempFeasibleSolution, timeLimitMin, logCsv, outputJson);

        System.out.println("\n[Phase 2] Optimization Complete.");
        System.out.println("Final Solution saved to: " + outputJson);

        // Cleanup
        new java.io.File(tempFeasibleSolution).delete();

      } else {
        System.err.println("\n[Phase 1] FAILED: Could not find a 0-violation solution within time limit.");
        System.err.println("          Simulated Annealing phase skipped.");
        System.err.println("          Best effort solution saved to: " + tempFeasibleSolution);
        // Optionally move temp to output if wanted, but user asked for strictly
        // feasible first.
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
