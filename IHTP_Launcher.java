import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

public class IHTP_Launcher extends JFrame {
    // --- Konfigurasi default ---
    private static final String DEFAULT_INSTANCES_DIR = "./ihtc2024_competition_instances";
    private static final String DEFAULT_TESTS_DIR = "./ihtc2024_test_dataset";
    private static final String DEFAULT_JSON_JAR_NAME = "json-20250107.jar";
    private static final String DEFAULT_RUNTIME_MIN = "10";

    // --- UI utama ---
    private JList<FileItem> datasetList;
    private JTextField runtimeField, violField, solField, jarField, runsField;
    private JTextArea consoleArea;
    private JButton runBtn, abortBtn, refreshBtn, browseDsBtn, loadLogBtn, validateBtn; // + validate
    private JCheckBox liveUpdateBox;
    private JCheckBox followDatasetBox;
    private JCheckBox autoValidateBox; // + auto validate

    // --- Grafik ---
    private JComboBox<String> xColumnCombo, yColumnCombo;
    private ChartPanel chartPanel;
    private File currentLogFile;
    private long lastLogMtime = -1;
    private javax.swing.Timer liveTimer;

    // --- Proses eksekusi ---
    private SwingWorker<Integer, String> worker;
    private volatile Process currentProcess;

    // --- Dataset list cache ---
    private final java.util.List<FileItem> items = new ArrayList<>();

    public IHTP_Launcher() {
        super("IHTP Launcher");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(buildUI());
        pack();
        setLocationRelativeTo(null);

        liveTimer = new javax.swing.Timer(1000, e -> liveReload());
        liveTimer.setRepeats(true);

        refreshDatasets();
    }

    private Container buildUI() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        // === FORM BARIS ATAS ===
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;

        // JSON jar
        form.add(new JLabel("JSON JAR:"), gc);
        jarField = new JTextField(findDefaultJarPath());
        gc.gridx = 1;
        gc.weightx = 1;
        form.add(jarField, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        JButton browseJar = new JButton("Browse");
        browseJar.addActionListener(e -> chooseFile(jarField, "Pilih json-20250107.jar", "jar"));
        form.add(browseJar, gc);

        // Dataset
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(new JLabel("Dataset (.json):"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        datasetList = new JList<>();
        datasetList.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        datasetList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && followDatasetBox.isSelected())
                updateOutputsFromDataset();
        });
        JScrollPane dsScroll = new JScrollPane(datasetList);
        dsScroll.setPreferredSize(new Dimension(200, 80)); // Tampilkan beberapa baris
        form.add(dsScroll, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        JPanel dsBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshDatasets());
        browseDsBtn = new JButton("Browse");
        browseDsBtn.addActionListener(e -> addDatasetFromChooser());
        dsBtns.add(refreshBtn);
        dsBtns.add(browseDsBtn);
        form.add(dsBtns, gc);

        // Ikuti nama dataset untuk output
        gc.gridy++;
        gc.gridx = 1;
        gc.weightx = 1;
        followDatasetBox = new JCheckBox("Ikuti nama dataset untuk output");
        followDatasetBox.setSelected(true);
        form.add(followDatasetBox, gc);

        // Algo Selection
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(new JLabel("Mode:"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        String[] algos = { "Standard (Constructive + ILS)", "Optimizer (Combinatorial SA)", "Optimizer (Hill Climbing)",
                "SA Only (From Existing Solution)", "QL-SA (Quantum Learning SA)" };
        modeCombo = new JComboBox<>(algos);
        modeCombo.setSelectedIndex(1); // Default to Optimizer as per request
        modeCombo.addActionListener(e -> updateOutputsFromDataset());
        form.add(modeCombo, gc);

        // Seed
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(new JLabel("Runtime (min):"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        runtimeField = new JTextField(DEFAULT_RUNTIME_MIN);
        form.add(runtimeField, gc);

        // Runs per instance
        gc.gridx = 2;
        gc.weightx = 0;
        JPanel runsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        runsPanel.add(new JLabel("Runs/inst:"));
        runsField = new JTextField("1", 3);
        runsPanel.add(runsField);

        // Custom Tag
        runsPanel.add(new JLabel("Tag:"));
        tagField = new JTextField(6);
        runsPanel.add(tagField);

        form.add(runsPanel, gc);

        // Violation CSV
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(new JLabel("Violation CSV:"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        violField = new JTextField();
        form.add(violField, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        JButton browseViol = new JButton("Simpan ke");
        browseViol.addActionListener(e -> chooseSave(violField, "Simpan violation CSV", "csv"));
        form.add(browseViol, gc);

        // Solution JSON
        gc.gridy++;
        gc.gridx = 0;
        gc.weightx = 0;
        form.add(new JLabel("Solution JSON:"), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        solField = new JTextField();
        form.add(solField, gc);
        gc.gridx = 2;
        gc.weightx = 0;
        JButton browseSol = new JButton("Simpan ke");
        browseSol.addActionListener(e -> chooseSave(solField, "Simpan solution JSON", "json"));
        form.add(browseSol, gc);

        // Tombol eksekusi
        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 3;
        gc.weightx = 0;
        JPanel runPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        runBtn = new JButton("Jalankan");
        runBtn.addActionListener(e -> runSolution());
        abortBtn = new JButton("Abort");
        abortBtn.setEnabled(false);
        abortBtn.addActionListener(e -> abortProcess());
        loadLogBtn = new JButton("Load Log");
        loadLogBtn.addActionListener(e -> loadLogFromChooser());
        validateBtn = new JButton("Validate"); // NEW
        validateBtn.addActionListener(e -> runValidator()); // NEW
        autoValidateBox = new JCheckBox("Auto-validate setelah run"); // NEW
        autoValidateBox.setSelected(true);
        liveUpdateBox = new JCheckBox("Live update");
        liveUpdateBox.addActionListener(e -> toggleLive());

        runPanel.add(runBtn);
        runPanel.add(abortBtn);
        runPanel.add(loadLogBtn);
        runPanel.add(validateBtn); // NEW
        runPanel.add(autoValidateBox);// NEW
        runPanel.add(liveUpdateBox);
        form.add(runPanel, gc);

        root.add(form, BorderLayout.NORTH);

        // --- Chart & Console ---
        chartPanel = new ChartPanel();

        consoleArea = new JTextArea();
        consoleArea.setEditable(false);
        consoleArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane consoleScroll = new JScrollPane(consoleArea);
        consoleScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Console / Log"));

        JPanel chartWrap = new JPanel(new BorderLayout());
        chartWrap.setBorder(javax.swing.BorderFactory.createTitledBorder("Live Chart"));
        chartWrap.add(chartPanel, BorderLayout.CENTER);

        // Column selectors for chart
        JPanel chartCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        chartCtrl.add(new JLabel("X:"));
        xColumnCombo = new JComboBox<>();
        chartCtrl.add(xColumnCombo);
        chartCtrl.add(new JLabel("Y:"));
        yColumnCombo = new JComboBox<>();
        chartCtrl.add(yColumnCombo);

        JButton plotBtn = new JButton("Refresh Plot");
        plotBtn.addActionListener(e -> plotCurrentSelection());
        chartCtrl.add(plotBtn);

        chartWrap.add(chartCtrl, BorderLayout.NORTH);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, chartWrap,
                consoleScroll);
        split.setResizeWeight(0.6);
        root.add(split, BorderLayout.CENTER);

        return root;
    }

    // UI Field
    private JComboBox<String> modeCombo;
    private JTextField tagField; // Added tagField

    // ================== DATASET SCAN ==================
    private void refreshDatasets() {
        items.clear();
        scanDir(DEFAULT_INSTANCES_DIR, "[instances]");
        scanDir(DEFAULT_TESTS_DIR, "[tests]");
        if (items.isEmpty()) {
            append("Tidak ada .json di '" + DEFAULT_INSTANCES_DIR + "' atau '" + DEFAULT_TESTS_DIR + "'.\n");
        } else {
            datasetList.setListData(items.toArray(new FileItem[0]));
            if (!items.isEmpty())
                datasetList.setSelectedIndex(0);

            if (followDatasetBox.isSelected())
                updateOutputsFromDataset();
        }
    }

    private void scanDir(String dir, String tag) {
        File d = new File(dir);
        if (!d.exists())
            return;
        File[] list = d.listFiles((f, name) -> name.toLowerCase().endsWith(".json"));
        if (list == null)
            return;
        Arrays.sort(list);
        for (File f : list)
            items.add(new FileItem(f.getAbsoluteFile(), tag + " " + f.getName()));
    }

    private void updateOutputsFromDataset() {
        java.util.List<FileItem> sels = datasetList.getSelectedValuesList();
        if (sels == null || sels.isEmpty())
            return;

        if (sels.size() == 1) {
            FileItem sel = sels.get(0);
            String base = baseName(sel.file.getName());

            int mode = (modeCombo != null) ? modeCombo.getSelectedIndex() : 1;
            String suffix = "";
            if (mode == 1)
                suffix = "_SA";
            else if (mode == 2)
                suffix = "_HC";
            else if (mode > 2)
                suffix = "_SA";

            if (mode == 0) {
                violField.setText("violation_log/violation_log_" + base + ".csv");
                solField.setText("solutions/solution_" + base + ".json");
            } else {
                violField.setText("logs_optimization/logs_" + base + ".csv");
                solField.setText("solution_SA/solution_" + base + suffix + ".json");
            }

            violField.setEnabled(true);
            solField.setEnabled(true);
        } else {
            violField.setText("(Auto)");
            solField.setText("(Auto)");
            violField.setEnabled(false);
            solField.setEnabled(false);
        }
    }

    // ================== RUN / ABORT ==================
    private void runSolution() {
        // ... (existing checks) ...
        java.util.List<FileItem> sels = datasetList.getSelectedValuesList();
        if (sels == null || sels.isEmpty()) {
            error("Pilih minimal satu dataset.");
            return;
        }

        // Common params
        String runtime = runtimeField.getText().trim().isEmpty() ? DEFAULT_RUNTIME_MIN : runtimeField.getText().trim();
        String jar = jarField.getText().trim();
        String tag = tagField.getText().trim(); // Get tag
        int runsPerInstance = 1;
        try {
            runsPerInstance = Integer.parseInt(runsField.getText().trim());
            if (runsPerInstance < 1)
                runsPerInstance = 1;
        } catch (Exception e) {
            runsPerInstance = 1;
        }

        int mode = modeCombo.getSelectedIndex(); // 0=Std, 1=Opt(SA), 2=Opt(HC), 3=SA, 4=QL-SA

        if (!new File(jar).isFile()) {
            error("json-20250107.jar tidak ditemukan:\n" + jar);
            return;
        }

        // UI Reset
        consoleArea.setText("");
        setRunState(true);

        if (!liveUpdateBox.isSelected()) {
            liveUpdateBox.setSelected(true);
            liveTimer.start();
        }

        final int finalRunsPerInstance = runsPerInstance;

        // Logic Worker
        worker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                String classpath = "bin" + File.pathSeparator + "." + File.pathSeparator + jar;
                int totalFailures = 0;
                int runsCount = finalRunsPerInstance; // effectively final workaround

                for (int i = 0; i < sels.size(); i++) {
                    FileItem item = sels.get(i);
                    String dsPath = item.file.getAbsolutePath();
                    String base = baseName(item.file.getName());

                    for (int run = 1; run <= runsCount; run++) {
                        // Determine output paths
                        String vPath, sPath;
                        String saSuffix = "";
                        if (mode == 1)
                            saSuffix = "_SA";
                        else if (mode == 2)
                            saSuffix = "_HC";
                        else if (mode > 2)
                            saSuffix = "_SA";

                        if (sels.size() == 1 && violField.isEnabled() && runsCount == 1 && tag.isEmpty()) {
                            vPath = violField.getText().trim();
                            sPath = solField.getText().trim();
                        } else {

                            boolean useRunSuffix = (runsCount > 1 || !tag.isEmpty());
                            String runSuffix = useRunSuffix ? "_" + run : "";
                            String tagSuffix = tag.isEmpty() ? "" : "_" + tag;

                            String finalSuffix = tagSuffix + runSuffix;

                            vPath = "logs_optimization/logs_" + base + finalSuffix + ".csv";
                            sPath = "solution_SA/solution_" + base + saSuffix + finalSuffix + ".json";

                            if (mode == 0) {
                                // Revert to old standard for mode 0
                                // Standard doesn't use SA suffix but let's keep consistent naming
                                vPath = "violation_log/violation_log_" + base + finalSuffix + ".csv";
                                sPath = "solutions/solution_" + base + finalSuffix + ".json";
                            }
                        }

                        ensureParentDir(vPath);
                        ensureParentDir(sPath);

                        // Setup live chart monitoring for this file
                        currentLogFile = new File(vPath);
                        lastLogMtime = -1;

                        String label = item.label + (runsCount > 1 ? " [Run " + run + "]" : "");
                        publish("\n>>> [" + (i + 1) + "/" + sels.size() + "] Memproses: " + label + " (Mode: "
                                + modeCombo.getSelectedItem() + ")\n");
                        publish("    Output Log: " + vPath + "\n");

                        // -- RUN SOLVER --
                        java.util.List<String> cmd = new ArrayList<>();
                        cmd.add("java");
                        cmd.add("-cp");
                        cmd.add(classpath);

                        if (mode == 0) {
                            // Standard
                            cmd.add("IHTP_Solution");
                            cmd.add(dsPath);
                            cmd.add(runtime); // minutes
                            cmd.add(vPath);
                            cmd.add(sPath);
                        } else if (mode == 1) {
                            // IHTP_Optimizer (Phase 1 + SA)
                            cmd.add("IHTP_Optimizer");
                            cmd.add(dsPath);
                            cmd.add(runtime); // minutes
                            cmd.add(vPath);
                            cmd.add(sPath);
                        } else if (mode == 2) {
                            // IHTP_Optimizer_HillClimbing (Phase 1 + HC)
                            cmd.add("IHTP_Optimizer_HillClimbing");
                            cmd.add(dsPath);
                            cmd.add(runtime); // minutes
                            cmd.add(vPath);
                            cmd.add(sPath);
                        } else if (mode == 3) {
                            // IHTP_SA Only
                            cmd.add("IHTP_SA");
                            cmd.add(dsPath);
                            // Convert min to hours (double)
                            double hours = Double.parseDouble(runtime) / 60.0;
                            cmd.add(String.valueOf(hours));
                            cmd.add(vPath);
                            cmd.add(sPath);
                            // Assume input solution exists at "solutions/solution_<base>.json" ???
                            // Or standard loc `solutions/solution_<base>.json`
                            String inferredInputSol = "solutions/solution_" + base + ".json";
                            File fSol = new File(inferredInputSol);
                            if (fSol.exists()) {
                                cmd.add(inferredInputSol);
                            } else {
                                publish("[WARN] Input solution not found: " + inferredInputSol
                                        + ". SA may fail or use default.\n");
                            }
                        } else if (mode == 4) {
                            // QL-SA (Quantum Learning SA)
                            // Use IHTP_Optimizer_QLSA (Unified 2-Phase)
                            cmd.add("IHTP_Optimizer_QLSA");
                            cmd.add(dsPath);
                            cmd.add(runtime); // minutes
                            cmd.add(vPath);
                            cmd.add(sPath);
                        }

                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        currentProcess = p;

                        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                publish(line + "\n");
                            }
                        }
                        int code = p.waitFor();
                        currentProcess = null;

                        if (code != 0) {
                            publish("\n[!] Gagal (Exit code " + code + ")\n");
                            totalFailures++;
                        } else {
                            publish("\n[OK] Solver selesai.\n");
                            // -- AUTO VALIDATE --
                            if (autoValidateBox.isSelected()) {
                                publish("    Validating...\n");
                                // Synchronous validation
                                java.util.List<String> vCmd = new ArrayList<>();
                                vCmd.add("java");
                                vCmd.add("-cp");
                                vCmd.add(classpath);
                                vCmd.add("IHTP_Validator");
                                vCmd.add(dsPath);
                                vCmd.add(sPath);

                                ProcessBuilder vPb = new ProcessBuilder(vCmd);
                                vPb.redirectErrorStream(true);
                                Process vP = vPb.start();
                                currentProcess = vP;

                                try (BufferedReader vR = new BufferedReader(
                                        new InputStreamReader(vP.getInputStream()))) {
                                    String vLine;
                                    while ((vLine = vR.readLine()) != null)
                                        publish("    [VAL] " + vLine + "\n");
                                }
                                int vCode = vP.waitFor();
                                currentProcess = null;

                                if (vCode == 0)
                                    publish("    Validator: OK.\n");
                                else {
                                    publish("    Validator: FOUND ISSUES (code " + vCode + ").\n");
                                }
                            }
                        }

                        // Jeda sedikit antar instance
                        if (i < sels.size() - 1 || run < runsCount)
                            Thread.sleep(1000);

                        if (isCancelled())
                            break;
                    }
                    if (isCancelled())
                        break;
                }
                return totalFailures;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String s : chunks)
                    append(s);
            }

            @Override
            protected void done() {
                setRunState(false);
                try {
                    int failures = get();
                    if (isCancelled()) {
                        append("\n=== Batch Stopped/Cancelled ===\n");
                    } else {
                        append("\n=== Batch Complete. Total failures: " + failures + " ===\n");
                        if (datasetList.getSelectedValuesList().size() == 1 && failures == 0) {
                            // If single run successful, load log to chart automatically
                            if (currentLogFile != null && currentLogFile.isFile() && !liveUpdateBox.isSelected())
                                loadViolationLog(currentLogFile, null, null);
                        }
                    }
                } catch (Exception ex) {
                    append("\n=== Error sistem: " + ex.getMessage() + " ===\n");
                }
            }
        };
        worker.execute();
    }

    private void abortProcess() {
        if (currentProcess != null) {
            append("\n== Menghentikan proses ==\n");
            try {
                currentProcess.destroy();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                if (currentProcess.isAlive())
                    currentProcess.destroyForcibly();
            } catch (Exception ex) {
                append("Abort error: " + ex.getMessage() + "\n");
            }
        }
        if (worker != null)
            worker.cancel(true);
        setRunState(false);
    }

    private void setRunState(boolean running) {
        runBtn.setEnabled(!running);
        abortBtn.setEnabled(running);
        refreshBtn.setEnabled(!running);
        browseDsBtn.setEnabled(!running);
        loadLogBtn.setEnabled(!running);
        validateBtn.setEnabled(!running); // disable validate saat proses lain berjalan
        // liveUpdateBox boleh diubah kapan pun
    }

    // ================== VALIDATOR ==================
    private void runValidator() {
        java.util.List<FileItem> sels = datasetList.getSelectedValuesList();
        if (sels == null || sels.isEmpty()) {
            error("Pilih dataset terlebih dahulu.");
            return;
        }

        String jar = jarField.getText().trim();
        if (!new File(jar).isFile()) {
            error("json-20250107.jar tidak ditemukan:\n" + jar);
            return;
        }

        consoleArea.setText("");
        setRunState(true);

        worker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                String classpath = "bin" + File.pathSeparator + "." + File.pathSeparator + jar;
                int fails = 0;

                for (FileItem item : sels) {
                    String dsPath = item.file.getAbsolutePath();
                    String base = baseName(item.file.getName());
                    String solPath;
                    if (sels.size() == 1 && solField.isEnabled()) {
                        solPath = solField.getText().trim();
                    } else {
                        solPath = "solutions/solution_" + base + ".json";
                    }

                    if (!new File(solPath).isFile()) {
                        publish("Skip " + item.label + ": File output/solusi tidak ditemukan (" + solPath + ")\n");
                        continue;
                    }

                    java.util.List<String> cmd = new ArrayList<>();
                    cmd.add("java");
                    cmd.add("-cp");
                    cmd.add(classpath);
                    cmd.add("IHTP_Validator");
                    cmd.add(dsPath);
                    cmd.add(solPath);

                    publish("\n> Validasi: " + item.label + "\n");
                    // publish("> " + String.join(" ", cmd) + "\n");

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    currentProcess = p;
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null)
                            publish("  " + line + "\n");
                    }
                    int code = p.waitFor();
                    currentProcess = null;
                    if (code != 0)
                        fails++;
                }
                return fails;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String s : chunks)
                    append(s);
            }

            @Override
            protected void done() {
                setRunState(false);
                try {
                    int fails = get();
                    append("\n== Validator Batch Selesai. Masalah ditemukan: " + fails + " ==\n");
                } catch (Exception ex) {
                    append("\n== Validator gagal: " + ex.getMessage() + " ==\n");
                }
            }
        };
        worker.execute();
    }

    // ================== LOG LOADER & GRAPH ==================
    private void loadLogFromChooser() {
        JFileChooser ch = new JFileChooser(new File("."));
        ch.setDialogTitle("Pilih violation_log (.csv)");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files", "csv"));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = ch.getSelectedFile();
            loadViolationLog(f, null, null);
        }
    }

    private void loadViolationLog(File f, String preferX, String preferY) {
        if (f == null || !f.isFile()) {
            error("File tidak ditemukan:\n" + f);
            return;
        }
        try {
            LogData data = readCsv(f);
            if (data.headers.isEmpty() || data.rows.isEmpty()) {
                error("CSV kosong atau tidak memiliki header.");
                return;
            }
            fillColumnCombos(data, preferX, preferY);
            plotFromData(data, (String) xColumnCombo.getSelectedItem(), (String) yColumnCombo.getSelectedItem());
            currentLogFile = f;
            lastLogMtime = f.lastModified();
        } catch (Exception ex) {
            error("Gagal membaca CSV: " + ex.getMessage());
        }
    }

    private void fillColumnCombos(LogData data, String preferX, String preferY) {
        xColumnCombo.removeAllItems();
        yColumnCombo.removeAllItems();
        for (String h : data.headers) {
            xColumnCombo.addItem(h);
            yColumnCombo.addItem(h);
        }

        String xGuess = (preferX != null) ? preferX : guessIterationColumn(data.headers);
        String yGuess = (preferY != null) ? preferY : guessPenaltyHCColumn(data.headers);
        if (xGuess == null)
            xGuess = data.headers.get(0);
        if (yGuess == null)
            yGuess = data.headers.get(data.headers.size() - 1);

        xColumnCombo.setSelectedItem(xGuess);
        yColumnCombo.setSelectedItem(yGuess);
    }

    private void plotCurrentSelection() {
        if (currentLogFile == null || !currentLogFile.isFile())
            return;
        try {
            LogData data = readCsv(currentLogFile);
            String xCol = (String) xColumnCombo.getSelectedItem();
            String yCol = (String) yColumnCombo.getSelectedItem();
            if (xCol == null || yCol == null)
                return;
            plotFromData(data, xCol, yCol);
        } catch (Exception ignored) {
        }
    }

    private void plotFromData(LogData data, String xCol, String yCol) {
        int xi = data.headers.indexOf(xCol);
        int yi = data.headers.indexOf(yCol);
        java.util.List<Double> xs = new ArrayList<>();
        java.util.List<Double> ys = new ArrayList<>();

        boolean xIsNumeric = isLikelyNumericColumn(data, xi);

        for (java.util.List<String> row : data.rows) {
            Double y = parseDoubleSafe(row, yi);
            if (y == null)
                continue;
            Double x;
            if (xIsNumeric) {
                x = parseDoubleSafe(row, xi);
                if (x == null)
                    continue;
            } else {
                x = (double) (xs.size() + 1);
            }
            xs.add(x);
            ys.add(y);
        }
        chartPanel.setSeries(xs, ys, "X: " + xCol + "   Y: " + yCol);
    }

    private void toggleLive() {
        if (liveUpdateBox.isSelected())
            liveTimer.start();
        else
            liveTimer.stop();
    }

    // Real-time reload tiap 1 detik, termasuk saat file baru dibuat
    private void liveReload() {
        if (currentLogFile == null)
            return;
        if (!currentLogFile.exists())
            return;
        long mt = currentLogFile.lastModified();
        if (lastLogMtime == -1) {
            loadViolationLogSilent(currentLogFile);
            lastLogMtime = mt;
            return;
        }
        if (mt != lastLogMtime) {
            lastLogMtime = mt;
            plotCurrentSelection();
        }
    }

    // Versi silent agar tidak pop-up error saat file masih kosong/ditulis
    private void loadViolationLogSilent(File f) {
        try {
            LogData data = readCsv(f);
            if (data.headers.isEmpty())
                return;
            if (xColumnCombo.getItemCount() == 0)
                fillColumnCombos(data, null, null);
            String xCol = (String) xColumnCombo.getSelectedItem();
            String yCol = (String) yColumnCombo.getSelectedItem();
            if (xCol == null || yCol == null)
                return;
            plotFromData(data, xCol, yCol);
        } catch (Exception ignored) {
        }
    }

    // ================== CSV helpers ==================
    private static class LogData {
        java.util.List<String> headers = new ArrayList<>();
        java.util.List<java.util.List<String>> rows = new ArrayList<>();
    }

    private LogData readCsv(File f) throws IOException {
        LogData data = new LogData();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String header = br.readLine();
            if (header == null)
                return data;
            data.headers = parseCsvLine(header);
            String line;
            while ((line = br.readLine()) != null)
                data.rows.add(parseCsvLine(line));
        }
        int H = data.headers.size();
        for (java.util.List<String> r : data.rows) {
            if (r.size() < H)
                while (r.size() < H)
                    r.add("");
            else if (r.size() > H)
                while (r.size() > H)
                    r.remove(r.size() - 1);
        }
        return data;
    }

    // CSV sederhana (handle tanda kutip dan koma)
    private java.util.List<String> parseCsvLine(String s) {
        java.util.List<String> out = new ArrayList<>();
        if (s == null)
            return out;
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                if (inQ && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else
                    inQ = !inQ;
            } else if (c == ',' && !inQ) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private boolean isLikelyNumericColumn(LogData d, int idx) {
        int ok = 0, seen = 0;
        for (java.util.List<String> r : d.rows) {
            if (idx < 0 || idx >= r.size())
                continue;
            String v = r.get(idx);
            if (v == null || v.isEmpty())
                continue;
            seen++;
            if (toDouble(v) != null)
                ok++;
            if (seen >= 20)
                break;
        }
        return ok >= Math.max(3, (int) Math.round(seen * 0.7));
    }

    private Double parseDoubleSafe(java.util.List<String> row, int idx) {
        if (idx < 0 || idx >= row.size())
            return null;
        return toDouble(row.get(idx));
    }

    private Double toDouble(String s) {
        try {
            String t = s.replaceAll("[^0-9eE+\\-\\.]", "");
            if (t.isEmpty() || t.equals("-") || t.equals("+"))
                return null;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return null;
        }
    }

    private String guessIterationColumn(java.util.List<String> headers) {
        String[] keys = { "iteration", "iter", "it", "step", "round" };
        for (String h : headers) {
            String L = h.toLowerCase();
            for (String k : keys)
                if (L.equals(k) || L.contains(k))
                    return h;
        }
        return null; // fallback pakai index baris
    }

    private String guessPenaltyHCColumn(java.util.List<String> headers) {
        for (String h : headers) {
            String L = h.toLowerCase();
            if (L.contains("penalty") && L.contains("hc"))
                return h;
        }
        for (String h : headers) {
            String L = h.toLowerCase();
            if (L.equals("hc") || L.contains("hc"))
                return h;
        }
        // New: Support "Cost" or "Objective"
        for (String h : headers) {
            String L = h.toLowerCase();
            if (L.contains("cost") || L.contains("score") || L.contains("obj"))
                return h;
        }
        // Support "Temperature" as fallback
        for (String h : headers) {
            if (h.toLowerCase().contains("temp"))
                return h;
        }
        return null;
    }

    // ================== UTIL LAIN ==================
    private void append(String s) {
        consoleArea.append(s);
        consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Info", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void ensureParentDir(String path) {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private void chooseFile(JTextField target, String title, String ext) {
        JFileChooser ch = new JFileChooser(new File("."));
        ch.setDialogTitle(title);
        if (ext != null)
            ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*." + ext, ext));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(ch.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseSave(JTextField target, String title, String ext) {
        JFileChooser ch = new JFileChooser(new File("."));
        ch.setDialogTitle(title);
        if (ext != null)
            ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*." + ext, ext));
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String p = ch.getSelectedFile().getAbsolutePath();
            if (ext != null && !p.toLowerCase().endsWith("." + ext))
                p += "." + ext;
            target.setText(p);
        }
    }

    private void addDatasetFromChooser() {
        JFileChooser ch = new JFileChooser(new File("."));
        ch.setDialogTitle("Pilih dataset .json");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = ch.getSelectedFile();
            FileItem it = new FileItem(f, "[custom] " + f.getName());
            // Tambahkan ke items dan list model
            items.add(it);
            // Re-set list data (simple approach)
            datasetList.setListData(items.toArray(new FileItem[0]));
            datasetList.setSelectedValue(it, true);
            if (followDatasetBox.isSelected())
                updateOutputsFromDataset();
        }
    }

    private String findDefaultJarPath() {
        File wd = new File(".", DEFAULT_JSON_JAR_NAME);
        if (wd.isFile())
            return wd.getPath();
        try {
            Path jarDir = Paths.get(IHTP_Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent();
            File near = jarDir.resolve(DEFAULT_JSON_JAR_NAME).toFile();
            if (near.isFile())
                return near.getPath();
        } catch (Exception ignored) {
        }
        return DEFAULT_JSON_JAR_NAME;
    }

    // === Item dataset untuk combo ===
    private static class FileItem {
        final File file;
        final String label;

        FileItem(File file, String label) {
            this.file = file;
            this.label = label;
        }

        @Override
        public String toString() {
            return label + "   (" + relativize(file) + ")";
        }

        private static String relativize(File f) {
            try {
                Path p = f.toPath().toAbsolutePath().normalize();
                Path base = Paths.get(".").toAbsolutePath().normalize();
                Path rel = base.relativize(p);
                return rel.toString();
            } catch (Exception e) {
                return f.getAbsolutePath();
            }
        }
    }

    // === Panel grafik sederhana (tanpa lib eksternal) ===
    private static class ChartPanel extends JPanel {
        private java.util.List<Double> xs = Collections.emptyList();
        private java.util.List<Double> ys = Collections.emptyList();
        private String subtitle = "";

        public ChartPanel() {
            setPreferredSize(new Dimension(900, 360));
            setBackground(Color.WHITE);
        }

        public void setSeries(java.util.List<Double> xs, java.util.List<Double> ys, String subtitle) {
            this.xs = xs;
            this.ys = ys;
            this.subtitle = subtitle == null ? "" : subtitle;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int left = 60, right = 20, top = 28, bottom = 40;

            g.setColor(Color.BLACK);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
            g.drawString("Penalty HC per Iteration", left, top - 10);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            g.drawString(subtitle, left + 210, top - 10);

            int pw = w - left - right, ph = h - top - bottom;
            g.drawRect(left, top, pw, ph);

            if (xs.isEmpty() || ys.isEmpty()) {
                g.drawString("Belum ada data.", left + 10, top + 20);
                return;
            }

            double xmin = Collections.min(xs), xmax = Collections.max(xs);
            double ymin = Collections.min(ys), ymax = Collections.max(ys);
            if (xmax == xmin)
                xmax = xmin + 1;
            if (ymax == ymin)
                ymax = ymin + 1;

            g.setColor(new Color(230, 230, 230));
            for (int i = 1; i <= 5; i++) {
                int yy = top + (int) Math.round(ph * i / 6.0);
                g.drawLine(left, yy, left + pw, yy);
            }
            for (int i = 1; i <= 5; i++) {
                int xx = left + (int) Math.round(pw * i / 6.0);
                g.drawLine(xx, top, xx, top + ph);
            }

            g.setColor(Color.DARK_GRAY);
            g.setFont(g.getFont().deriveFont(10f));
            g.drawString(String.format("%.2f", ymin), 6, top + ph);
            g.drawString(String.format("%.2f", ymax), 6, top + 10);
            g.drawString(String.format("%.2f", xmin), left, h - 6);
            g.drawString(String.format("%.2f", xmax), left + pw - 40, h - 6);

            g.setColor(new Color(0, 102, 204));
            int n = Math.min(xs.size(), ys.size());
            int prevx = -1, prevy = -1;
            for (int i = 0; i < n; i++) {
                int xx = left + (int) Math.round((xs.get(i) - xmin) * pw / (xmax - xmin));
                int yy = top + ph - (int) Math.round((ys.get(i) - ymin) * ph / (ymax - ymin));
                if (i > 0)
                    g.drawLine(prevx, prevy, xx, yy);
                g.fillOval(xx - 2, yy - 2, 4, 4);
                prevx = xx;
                prevy = yy;
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new IHTP_Launcher().setVisible(true));
    }
}