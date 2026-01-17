#!/usr/bin/env python3
"""
Analyze a nurse/patient scheduling instance (i16.json) and print detailed metrics
useful for tuning scheduling flows.

Outputs:
 - Basic stats
 - Per-day/shift workload demand per skill level
 - Per-day/shift available capacity per skill level
 - Demand vs capacity ratios and bottlenecks
 - Room occupancy per day and capacity violations
 - Other distributions (age groups, length_of_stay, etc)

Usage:
  python3 scripts/analyze_instance.py ihtc2024_competition_instances/i16.json

"""

import json
import sys
from collections import defaultdict, Counter


def read_instance(path):
    with open(path) as f:
        return json.load(f)


def analyze(instance):
    days = instance.get('days')
    shift_types = instance.get('shift_types', [])
    s_cnt = len(shift_types)
    skill_levels = instance.get('skill_levels', 0)

    print('\n===== Instance summary =====')
    print('days:', days)
    print('shift count:', s_cnt, 'shift types:', shift_types)
    print('skill_levels:', skill_levels)

    occupants = instance.get('occupants', [])
    rooms = instance.get('rooms', [])
    nurses = instance.get('nurses', [])
    weights = instance.get('weights', {})

    print('\n--- Basic counts')
    print('occupants:', len(occupants))
    print('rooms:', len(rooms))
    print('nurses:', len(nurses))

    # distributions
    age_counter = Counter()
    gender_counter = Counter()
    los_counter = Counter()
    total_workload_per_occ = {}

    max_wl_len = 0

    for o in occupants:
        age_counter[o.get('age_group')] += 1
        gender_counter[o.get('gender')] += 1
        los = o.get('length_of_stay', 1)
        los_counter[los] += 1
        wl = o.get('workload_produced', [])
        total_workload_per_occ[o['id']] = sum(wl)
        max_wl_len = max(max_wl_len, len(wl))

    print('\n--- Occupant distributions')
    print('age groups:', dict(age_counter))
    print('genders:', dict(gender_counter))
    print('length_of_stay (hist):', dict(sorted(los_counter.items())))
    print('max workload per occupant array length:', max_wl_len)

    # rooms capacities
    room_caps = {r['id']: r['capacity'] for r in rooms}
    occs_by_room = defaultdict(list)
    for o in occupants:
        occs_by_room[o['room_id']].append(o['id'])

    print('\n--- Rooms')
    print('total rooms:', len(room_caps))
    print('rooms capacity (id: capacity):')
    for k, v in sorted(room_caps.items()):
        print(f'  {k:>4s}: {v}')
    print('occupants_per_room (counts):')
    for r, occs in occs_by_room.items():
        print(f'  {r:>4s}: {len(occs)}')

    # Build demand per day and shift per skill
    # Assumption: each occupant starts on day 0 and occupies subsequent days
    demand = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    # demand[day][shift][skill] = total workload

    for o in occupants:
        room = o['room_id']
        los = o['length_of_stay']
        workload = o.get('workload_produced', [])
        skills = o.get('skill_level_required', [])
        for d in range(los):
            for s in range(s_cnt):
                idx = d*s_cnt + s
                # Some workload arrays may be shorter; guard
                if idx < len(workload):
                    wl = workload[idx]
                    req_skill = skills[idx] if idx < len(skills) else 0
                    demand[d][shift_types[s]][req_skill] += wl

    # Build capacity per day & shift per skill from nurses
    capacity = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    nurses_by_skill = defaultdict(list)
    for n in nurses:
        ns = n.get('skill_level', 0)
        nurses_by_skill[ns].append(n['id'])
        for ws in n.get('working_shifts', []):
            d = ws['day']
            shift = ws['shift']
            cap = ws.get('max_load', 0)
            capacity[d][shift][ns] += cap

    # Sum capacity per shift with substitution (higher skill can cover lower skill)
    # We'll compute capacity_by_skill_ge[d][shift][s] = total capacity from nurses with skill>=s
    capacity_by_skill_ge = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    for d in range(days):
        for shift in shift_types:
            for s in range(skill_levels):
                # accumulate nurses with skill >= s
                cap = sum(v for lvl, v in capacity[d][shift].items() if lvl >= s)
                capacity_by_skill_ge[d][shift][s] = cap

    # Also sum demand totals by day/shift
    demand_total = defaultdict(lambda: defaultdict(int))
    for d in range(days):
        for shift in shift_types:
            for s in range(skill_levels):
                demand_total[d][shift] += demand[d][shift].get(s, 0)

    # Provide a day/shift report table
    print('\n--- Day/Shift Demand vs Capacity (subsumed)')
    header = ['day', 'shift', 'demand_total'] + [f'demand_skill_{s}' for s in range(skill_levels)] + ['capacity_total']
    print('\t'.join(header))
    bottlenecks = []
    for d in range(days):
        for shift in shift_types:
            dt = demand_total[d][shift]
            # cap (subsumed) is capacity_by_skill_ge[d][shift][0]
            cap = capacity_by_skill_ge[d][shift][0]
            row = [str(d), shift, str(dt)] + [str(demand[d][shift].get(s, 0)) for s in range(skill_levels)] + [str(cap)]
            print('\t'.join(row))
            if cap < dt:
                bottlenecks.append((d, shift, dt, cap))

    print('\nBottleneck shifts (demand > capacity):')
    for b in bottlenecks:
        d, shift, dt, cap = b
        print(f'  Day {d} {shift}: demand {dt} > capacity {cap} -> shortage {dt-cap}')

    # More detailed per-skill bottlenecks including substitution: demand at skill s
    # can be covered by nurses with skill >= s
    print('\n--- Per-skill potential shortfalls (with substitution allowed from higher-level nurses)')
    shortfalls = []
    for d in range(days):
        for shift in shift_types:
            for s in range(skill_levels):
                req = demand[d][shift].get(s, 0)
                cap_sub = capacity_by_skill_ge[d][shift].get(s, 0)
                if req > cap_sub:
                    shortfalls.append((d, shift, s, req, cap_sub))
    print('shortfalls for exact skill matching:')
    for s in shortfalls:
        d, shift, skill, req, cap = s
        print(f'  Day {d} {shift} skill {skill}: demand {req} > capacity {cap} -> shortage {req-cap}')

    # For each detected shortfall, find candidate nurses with higher skill nearby who could be reallocated
    def find_candidates(day, shift, min_skill):
        candidates = []
        # candidates are nurses with skill>=min_skill who have a working shift on day-1, day or day+1
        neighbors = [day-1, day, day+1]
        for n in nurses:
            if n.get('skill_level', 0) >= min_skill:
                for ws in n.get('working_shifts', []):
                    wd = ws['day']
                    wshift = ws['shift']
                    if wd in neighbors:
                        candidates.append((n['id'], wd, wshift))
                        break
        return candidates

    if shortfalls:
        print('\nCandidate reallocation suggestions for shortfalls (skill >= required)')
        for s in shortfalls:
            d, shift, skill, req, cap_sub = s
            cand = find_candidates(d, shift, skill)
            # Show up to 10 candidates
            print(f'  Day {d} {shift} skill {skill} shortage {req-cap_sub} -> candidates (id, day, shift): {cand[:10]}')

    # Room occupancy check per day
    print('\n--- Room occupancy per day (capacity v occupant count)')
    room_violations = []
    for d in range(days):
        occ_count = defaultdict(int)
        for o in occupants:
            if d < o.get('length_of_stay'):
                occ_count[o['room_id']] += 1
        for r, cnt in occ_count.items():
            cap = room_caps.get(r, 0)
            if cnt > cap:
                room_violations.append((d, r, cnt, cap))
    for v in room_violations:
        d, r, cnt, cap = v
        print(f'  Day {d} room {r}: occupants {cnt} > capacity {cap} -> overflow {cnt-cap}')

    print('\n--- Additional breakdowns')
    # longest LOS
    print('max length_of_stay:', max([o.get('length_of_stay', 0) for o in occupants]))
    # patients requiring highest total workload
    top_occ = sorted(total_workload_per_occ.items(), key=lambda x: x[1], reverse=True)[:10]
    print('Top 10 patients by total workload (sum of workload_produced):')
    for pid, w in top_occ:
        print(' ', pid, w)

    # summary recommendations based on findings
    print('\n--- Quick recommendations')
    if bottlenecks:
        print('Shifts exceeding total capacity detected: consider increasing nurse availability or redistributing workloads:')
        for b in bottlenecks[:10]:
            d, shift, dt, cap = b
            print(' ', f'Day {d} {shift}: shortage {dt-cap}')
    else:
        print('No total demand > total capacity bottlenecks detected')

    if shortfalls:
        print('Skill-specific shortfalls detected: consider up-skilling or scheduling higher skill nurses in these shifts')
    else:
        print('No per-skill exact shortfalls detected')

    if room_violations:
        print('Room capacity violations exist: consider moving patients to other rooms, or prevent admissions to full rooms')
    else:
        print('No room capacity violations detected')

    print('\nWeights summary from instance:')
    print(json.dumps(weights, indent=2))

    # Print nurses per skill summary and where skill-2 nurses are working
    print('\n--- Nurses by skill')
    for s in range(skill_levels-1, -1, -1):
        print(f'  skill {s}: {len(nurses_by_skill.get(s, []))} nurses -> {nurses_by_skill.get(s, [])[:10]}')
    print('\nskill-2 nurse working shifts (identify gaps):')
    for n in nurses:
        if n.get('skill_level') == skill_levels-1:
            shifts = [(ws['day'], ws['shift']) for ws in n.get('working_shifts', [])]
            print(' ', n['id'], 'shifts:', shifts)

    # Optional: write CSV outputs for day/shift demand and capacity
    try:
        import csv, os
        outdir = 'analysis_outputs'
        os.makedirs(outdir, exist_ok=True)
        with open(os.path.join(outdir, 'day_shift_demand_capacity.csv'), 'w', newline='') as outf:
            writer = csv.writer(outf)
            writer.writerow(['day', 'shift', 'demand_total'] + [f'demand_skill_{s}' for s in range(skill_levels)] + ['capacity_total'] + [f'cap_skill_ge_{s}' for s in range(skill_levels)])
            for d in range(days):
                for shift in shift_types:
                    row = [d, shift, demand_total[d][shift]] + [demand[d][shift].get(s, 0) for s in range(skill_levels)] + [capacity_by_skill_ge[d][shift][0]] + [capacity_by_skill_ge[d][shift][s] for s in range(skill_levels)]
                    writer.writerow(row)
        print('\nCSV outputs written to analysis_outputs/day_shift_demand_capacity.csv')
    except Exception as e:
        print('Error writing CSV outputs:', e)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('usage: python3 scripts/analyze_instance.py path_to_instance.json')
        sys.exit(1)
    path = sys.argv[1]
    inst = read_instance(path)
    analyze(inst)
