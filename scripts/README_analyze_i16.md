# Analyze i16 Instance (Nurse/Patient Scheduling)

This script analyzes the `i16.json` instance (in `ihtc2024_competition_instances`) and produces metrics and recommendations useful for "tuning the flow" — that is, adjusting scheduling heuristics and nurse assignments to avoid overloads.

## Files

- `scripts/analyze_instance.py` — main analysis script.
- `analysis_outputs/day_shift_demand_capacity.csv` — output CSV containing the per-day/shift demand and capacity numbers.

## How to run

```bash
python3 scripts/analyze_instance.py ihtc2024_competition_instances/i16.json
```

The script prints:

- Basic summary of the instance (days, shifts, counts)
- Occupant distributions (age, gender, length of stay)
- Room capacities and occupant counts
- Day/shift demand vs capacity (total and per-skill)
- Bottlenecks where demand > capacity
- Skill-specific shortfalls (considering higher-skill substitution)
- Candidate nurses who could be reallocated to cover shortfalls
- Top patients by workload (to help identify where to focus)

It also writes a CSV `analysis_outputs/day_shift_demand_capacity.csv` with the per-day/shift numbers for further analysis/plotting.

## Interpretation & Tuning Guidance

1. Total capacity bottleneck (demand > total capacity):

   - This is a hard bottleneck: consider increasing nurse capacity, hiring, or decrypting non-critical workloads.
   - If there are many such shortages, look to reassign nurse shifts, or consider overtime (if allowed).

2. Skill-specific shortfalls:

   - If a shortfall exists even after allowing higher-skilled nurses to cover (our script checks substitution), then consider upskilling (reassigning a skilled nurse) or bringing in an external high-skilled resource.
   - The script lists candidate nurses who work on nearby days or adjacent shifts: those are good candidates to swap with less-critical shifts.
   - Example suggestion: add at least X skill-2 capacity on day Y shift Z where shortage occurs by shifting assignments.

3. Room capacity violations:

   - Move patients across days/rooms or cancel elective admissions if capacity in a room is exceeded.
   - Spread out high-workload patients across multiple rooms if possible to balance nurse workload.

4. Continuity of care & priority rules (based on `weights`):
   - `weights` are included in the instance and can inform which soft constraints are important. If `continuity_of_care` is high, prefer to allocate the same nurse across days for same patient.

## Next steps for tuning the flow in the solver

- Add constraints to the solver objective that prioritize skill-down substitution only when necessary and keep skill-up substitution minimized to allow better assignment of higher-skilled staff where necessary.
- Include an optimization target: "minimize number of skill upgrades required" or reduce `nurse_eccessive_workload` weight if you want to allow more overtime.
- Focus on days/shifts reported as shortfall — start by reassigning high-skill nurses listed in the candidates output.

---

If you'd like, I can extend the script to:

- Produce plots (matplotlib) of daily demand vs capacity per skill
- Suggest explicit shift swap operations (e.g., swap nurse n012 day 1 early with nurse n025 day 1 late)
- Integrate with your solver to automatically propose ideal reassignments (requires more constraints modeling)

Tell me which of the above you'd like next. If you want me to also analyze other instances in the folder, I can run a batch analysis and output CSVs for each.
