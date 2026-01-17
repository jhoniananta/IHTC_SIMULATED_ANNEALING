// IHTP_Preprocess.java
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.HashMap;
import java.util.Map;

public class IHTP_Preprocess {
    private final IHTP_Input input;

    public IHTP_Preprocess(IHTP_Input input) {
        this.input = input;
    }

    /**
     * Versi deterministik lama: prioritas berdasarkan "maxSlot" (days *
     * compatibleRooms). Hanya memasukkan pasien mandatory (seperti implementasi
     * asal).
     */
    public PriorityQueue<IHTP_Input.Patient> queuePatientsBySlots() {
        int totalRooms = input.Rooms();

        // LANGKAH 1: hitung maxSlot semua patient dengan improved scoring
        for (IHTP_Input.Patient patient : input.patientsList) {
            if (!patient.mandatory) {
                patient.maxSlot = Integer.MAX_VALUE; // Optional patients last
                continue;
            }

            int feasibleDays = estimateFeasibleDays(patient);
            int compatibleRooms = countCompatibleRooms(patient, totalRooms);
            
            // Improved: penalize patients with tight windows MORE heavily
            int windowSize = Math.max(1, patient.surgeryDueDay - patient.surgeryReleaseDay + 1);
            int surgeryPenalty = (patient.surgeryDuration > 60) ? 2 : 1; // longer surgeries harder to fit
            
            patient.maxSlot = Math.max(1, (feasibleDays * compatibleRooms) / surgeryPenalty);
            
            // If window is very tight (<=3 days), make it even MORE priority
            if (windowSize <= 3) {
                patient.maxSlot = Math.max(1, patient.maxSlot / 3);
            } else if (windowSize <= 5) {
                patient.maxSlot = Math.max(1, patient.maxSlot / 2);
            }
        }

        // LANGKAH 2: comparator & queue
        Comparator<IHTP_Input.Patient> comparator = Comparator.comparingInt((IHTP_Input.Patient ps) -> ps.maxSlot)
                .thenComparingInt(ps -> (ps.surgeryDueDay - ps.surgeryReleaseDay))
                .thenComparingInt(ps -> -ps.surgeryDuration) // longer surgery first
                .thenComparing(ps -> ps.id);

        PriorityQueue<IHTP_Input.Patient> pq = new PriorityQueue<>(comparator);
        PriorityQueue<IHTP_Input.Patient> optional = new PriorityQueue<>(comparator);

        // LANGKAH 3: hanya mandatory
        for (IHTP_Input.Patient patient : input.patientsList) {
            if (patient.mandatory) {
                pq.add(patient);
            } else {
                optional.add(patient);
            }
        }

        if (pq.isEmpty()) {
            return optional;
        }

        return pq;
    }

    /*
     * ============================================================ VERSI BARU:
     * RANDOM-WEIGHTED PRIORITY x AVAILABILITY
     * ============================================================
     */

    /**
     * Prioritaskan pasien menggunakan skor: priorityScore = randomWeight *
     * availability di mana availability kami estimasi lebih informatif:
     * availability = feasibleDays * compatibleRooms feasibleDays = jumlah hari
     * dalam window pasien yang: - surgeon masih punya waktu (maxSurgeryTime[day] >
     * 0), dan - ada operating theater dengan availability[day] >= surgeryDuration
     * pasien.
     *
     * @param seed            seed RNG (untuk hasil yang reproducible)
     * @param minRandomWeight batas bawah bobot acak, misal 0.7
     * @param maxRandomWeight batas atas bobot acak, misal 1.3
     * @param includeOptional true untuk memasukkan pasien opsional juga (tetap
     *                        diurutkan), false untuk hanya mandatory (opsional
     *                        dibuang dari PQ seperti versi lama)
     */
    public PriorityQueue<IHTP_Input.Patient> queuePatientsByRandomWeightedSlots(long seed, double minRandomWeight,
            double maxRandomWeight, boolean includeOptional) {
        Random rnd = new Random(seed);
        return buildRandomWeightedQueue(rnd, minRandomWeight, maxRandomWeight, includeOptional);
    }

    /**
     * Overload tanpa seed (benar-benar acak).
     */
    public PriorityQueue<IHTP_Input.Patient> queuePatientsByRandomWeightedSlots(double minRandomWeight,
            double maxRandomWeight, boolean includeOptional) {
        Random rnd = new Random(); // non-deterministic
        return buildRandomWeightedQueue(rnd, minRandomWeight, maxRandomWeight, includeOptional);
    }

    /* ============================= Helpers ============================= */

    private PriorityQueue<IHTP_Input.Patient> buildRandomWeightedQueue(Random rnd, double minW, double maxW,
            boolean includeOptional) {
        int totalRooms = input.Rooms();

        // Simpan skor prioritas per pasien (dipakai comparator)
        Map<String, Double> priorityScore = new HashMap<>();

        for (IHTP_Input.Patient p : input.patientsList) {
            // Optional: kamu bisa exclude dari PQ atau ikut dimasukkan
            if (!includeOptional && !p.mandatory) {
                continue;
            }

            // Hitung availability yang lebih informatif
            int feasibleDays = estimateFeasibleDays(p);
            int compatibleRooms = countCompatibleRooms(p, totalRooms);
            int availability = Math.max(1, feasibleDays * compatibleRooms);

            // Update juga field maxSlot untuk info/debug downstream (tidak wajib)
            p.maxSlot = availability;

            // Bobot acak per pasien
            double w = drawWeight(rnd, minW, maxW);

            // Priority score = w * availability (SEMakin kecil makin prioritas)
            // Jika kamu ingin memastikan pasien paling sempit window-nya tetap di depan,
            // pilih rentang weight yang sempit (mis. 0.8..1.2) agar random tidak
            // mendominasi.
            double score = w * availability;

            // Optional patients bisa tetap ditaruh jauh di belakang (opsi):
            if (!p.mandatory && includeOptional) {
                // dorong ke belakang, namun tetap punya variasi
                score += 1e9; // angka besar supaya selalu di belakang mandatory
            }

            priorityScore.put(p.id, score);
        }

        // Comparator berdasarkan skor (ascending), lalu tie-breaker seperti versi lama
        Comparator<IHTP_Input.Patient> cmp = (a, b) -> {
            double sa = priorityScore.getOrDefault(a.id, Double.POSITIVE_INFINITY);
            double sb = priorityScore.getOrDefault(b.id, Double.POSITIVE_INFINITY);
            int c = Double.compare(sa, sb);
            if (c != 0)
                return c;

            // tie-breaker deterministik
            int daySpanA = Math.max(0, a.surgeryDueDay - a.surgeryReleaseDay);
            int daySpanB = Math.max(0, b.surgeryDueDay - b.surgeryReleaseDay);
            c = Integer.compare(daySpanA, daySpanB);
            if (c != 0)
                return c;

            return a.id.compareTo(b.id);
        };

        PriorityQueue<IHTP_Input.Patient> pq = new PriorityQueue<>(cmp);

        // Masukkan pasien sesuai includeOptional
        for (IHTP_Input.Patient p : input.patientsList) {
            if (includeOptional || p.mandatory) {
                // Pastikan pasien punya skor (filtering di atas)
                if (priorityScore.containsKey(p.id)) {
                    pq.add(p);
                }
            }
        }

        return pq;
    }

    private double drawWeight(Random rnd, double minW, double maxW) {
        if (minW > maxW) {
            double tmp = minW;
            minW = maxW;
            maxW = tmp;
        }
        double r = rnd.nextDouble(); // [0,1)
        return minW + r * (maxW - minW);
    }

    /**
     * Estimasi jumlah hari feasible dalam window pasien, mempertimbangkan: -
     * surgeon masih punya waktu di hari tsb (maxSurgeryTime[day] > 0) - ada minimal
     * satu OT dengan availability[day] >= durasi operasi pasien
     */
    private int estimateFeasibleDays(IHTP_Input.Patient p) {
        int start = Math.max(0, p.surgeryReleaseDay);
        int end = Math.min(input.Days() - 1, p.surgeryDueDay);
        if (end < start)
            return 1; // fallback minimal

        int feasible = 0;

        // surgeon info
        IHTP_Input.Surgeon s = input.surgeonsList.get(p.surgeon);

        for (int d = start; d <= end; d++) {
            boolean surgeonOk = (s.maxSurgeryTime != null && d < s.maxSurgeryTime.length && s.maxSurgeryTime[d] > 0);
            if (!surgeonOk)
                continue;

            boolean otOk = false;
            for (IHTP_Input.OperatingTheater ot : input.theatersList) {
                int cap = (ot.availability != null && d < ot.availability.length) ? ot.availability[d] : 0;
                if (cap >= p.surgeryDuration) {
                    otOk = true;
                    break;
                }
            }
            if (otOk)
                feasible++;
        }

        // minimal 1 agar tidak nol total
        return Math.max(1, feasible);
    }

    private int countCompatibleRooms(IHTP_Input.Patient p, int totalRooms) {
        if (p.incompatibleRooms == null || p.incompatibleRooms.length == 0) {
            return Math.max(1, totalRooms); // assume semua kompatibel
        }
        int incompatible = 0;
        int len = Math.min(totalRooms, p.incompatibleRooms.length);
        for (int i = 0; i < len; i++) {
            if (p.incompatibleRooms[i])
                incompatible++;
        }
        int compatible = totalRooms - incompatible;
        return Math.max(1, compatible);
    }

    private int clampDays(int release, int due) {
        int start = Math.max(0, release);
        int end = Math.min(input.Days() - 1, due);
        int days = end - start + 1;
        return Math.max(1, days);
    }
}