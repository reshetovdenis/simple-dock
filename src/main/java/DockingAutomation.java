import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class DockingAutomation {
    // Run configuration
    private static final String BASE_DIR = "/Users/adr/programming/docking";
    private static final String RECEPTOR_FILE_NAME = "/Users/adr/localGoogleDrive/CYFIP2_project/lab_journals/Denis_Reshetov/files/20230904/7usc_R87C_H_around_8.pdb";
    private static final String DOCKING_DIR = BASE_DIR + "/7usc_R87C_H_around_8_flex_8";
    private static final boolean FLEXIBLE = true;
    private static final int EXHAUSTIVENESS = 8;
    private static final String NPTS = "20 20 20";
    private static final String GRID_CENTER = "85.176 141.059 163.344";
    private static final String AMINO_ACID = "CYS87";
    private static final boolean SHOW_COMMANDS = false;

    private static final String LIGANDS_DIR = BASE_DIR + "/ligands_input";
    // Path to programs:
    private static final String PREPARE_RECEPTOR = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/prepare_receptor";
    private static final String VINA_SCRIPT = "/Users/adr/localGoogleDrive/CYFIP2_project/lab_journals/Denis_Reshetov/files/20230904/AutoDock-Vina/example/autodock_scripts";
    private static final String OBABEL = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/obabel";
    private static final String PYTHONSH = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/pythonsh";
    private static final String AUTOGRID4 = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/autogrid4";
    private static final String VINA = "/Users/adr/software/vina_1.2.5_mac_x86_64";
    // Don't change this:
    private static final String RECEPTOR_PBQT = DOCKING_DIR + "/receptor.pdbqt";
    private static final String RECEPTOR_RIGID = DOCKING_DIR + "/receptor_rigid.pdbqt";
    private static final String RECEPTOR_FLEX = DOCKING_DIR + "/receptor_flex.pdbqt";
    private static final String ADD_ATOM_TYPES = "SA";
    // END Don't change this


    public static void main(String[] args) {
        long startTime = System.nanoTime();
        // Call the method you want to measure time for
        dock();
        long endTime = System.nanoTime();
        long durationInNanoseconds = (endTime - startTime);
        double durationInMinutes = (double) durationInNanoseconds / 1_000_000_000 / 60;
        System.out.printf("Executed in: %.8f minutes%n", durationInMinutes);
    }

    public static void dock() {
        try {
            new File(DOCKING_DIR).mkdirs();
            prepareReceptor();
            File ligandsDirectory = new File(LIGANDS_DIR);

            File[] ligandFiles = Objects.requireNonNull(ligandsDirectory.listFiles((dir, name) -> name.endsWith(".sdf")));
            int totalLigands = ligandFiles.length;
            int processed = 0;
            for (File file : ligandFiles) {
                try {
                    processLigand(file.getName());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                ++processed;
                System.out.print("\rProcessed: " + processed + "/" + totalLigands);
            }
            System.out.println();//new line
            outputStatistics();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void outputStatistics() throws IOException {
        Path directoryPath = Paths.get(DOCKING_DIR);
        List<String> csvOutput = new ArrayList<>();
        csvOutput.add("Ligand, kcal/mol");

        File ligandsDirectory = new File(LIGANDS_DIR);
        for (File file : Objects.requireNonNull(ligandsDirectory.listFiles())) {
            if (file.getName().endsWith(".sdf")) {
                String ligandBaseName = file.getName().split("\\.")[0];
                String vinaResults = DOCKING_DIR + "/" + ligandBaseName + "/vina_results.txt";
                double bestDockingResult = extractBestDockingResult(new File(vinaResults));
                csvOutput.add(ligandBaseName + ", " + bestDockingResult);
            }
        }
        Files.write(directoryPath.resolve("docking_results.csv"), csvOutput);
    }

    private static double extractBestDockingResult(File file) throws IOException {
        double result = Double.NaN;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mode") && line.contains("affinity")) {
                    line = reader.readLine(); // Skip line
                    line = reader.readLine(); // Skip line
                    line = reader.readLine(); // Skip line
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        result = Double.parseDouble(parts[2]); // Extract the affinity value
                    }
                }
            }
        }
        return result;
    }

    private static void editGpfFile(String path, String atomTypes, String receptorFile) throws IOException {
        List<String> modifiedLines = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("npts")) {
                    modifiedLines.add("npts " + NPTS);
                } else if (line.trim().startsWith("gridcenter")) {
                    modifiedLines.add("gridcenter " + GRID_CENTER);
                } else if (line.trim().startsWith("receptor ")) {
                    modifiedLines.add("receptor " + receptorFile);
                } else if (line.trim().startsWith("ligand_types") && atomTypes != null) {
                    String types = line.trim();
                    if (!types.contains(" " + atomTypes + " ")) {
                        types = types.replace("ligand_types", "ligand_types " + atomTypes);
                    }
                    modifiedLines.add(types);
                } else {
                    modifiedLines.add(line);
                    if (line.trim().startsWith("smooth") && atomTypes != null) {
                        modifiedLines.add("map receptor_rigid.SA.map");
                    }
                }
            }
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            for (String line : modifiedLines) {
                bw.write(line);
                bw.newLine();
            }
        }
    }

    private static void prepareReceptor() throws IOException, InterruptedException {
        File receptorFile = new File(DOCKING_DIR + "/receptor.pdbqt");
        if (!receptorFile.exists()) {  // Check if the receptor has already been prepared
            executeCommand("cp " + RECEPTOR_FILE_NAME + " " + DOCKING_DIR + "/receptor.pdb");
            File prepareReceptorLog = new File(DOCKING_DIR + "/prepareReceptor.log");
            executeCommand(PREPARE_RECEPTOR + " -r " + DOCKING_DIR + "/receptor.pdb -o " + DOCKING_DIR + "/receptor.pdbqt", null, prepareReceptorLog);
        }
        if (FLEXIBLE) {
            File receptorFlex = new File(RECEPTOR_FLEX);
            File receptorRigid = new File(RECEPTOR_RIGID);
            if (!receptorFlex.exists() || !receptorRigid.exists()) {
                executeCommand(PYTHONSH + " " + VINA_SCRIPT + "/prepare_flexreceptor.py -r " + RECEPTOR_PBQT + " -s " + AMINO_ACID, new File(DOCKING_DIR));
            }
        }
    }

    private static void processLigand(String ligandFileName) throws IOException, InterruptedException {
        String ligandBaseName = ligandFileName.split("\\.")[0];
        String ligandDir = DOCKING_DIR + "/" + ligandBaseName;
        new File(ligandDir).mkdirs();
        File workingDirectory = new File(ligandDir);
        ligandBaseName = "ligand";

        File babelOut = new File(ligandDir + "/babel.log");
        executeCommand(OBABEL + " " + LIGANDS_DIR + "/" + ligandFileName + " -O " + ligandDir + "/" + ligandBaseName + "_hyd.sdf -h", null, babelOut);
        executeCommand("/usr/local/bin/python3 /Library/Frameworks/Python.framework/Versions/3.6/bin/mk_prepare_ligand.py -i " + ligandDir + "/" + ligandBaseName + "_hyd.sdf -o " + ligandDir + "/" + ligandBaseName + ".pdbqt");

        String receptorToRun = RECEPTOR_PBQT;
        String configLigandTypes = null;
        String configReceptor = "../receptor.pdbqt";
        String gpf = ligandDir + "/receptor.gpf";
        String glg = ligandDir + "/receptor.glg";
        String mapsPrefix = "receptor";
        String vinaCommandParam = "";

        if (FLEXIBLE) {
            receptorToRun = RECEPTOR_RIGID;
            configLigandTypes = ADD_ATOM_TYPES;
            configReceptor = "../receptor_rigid.pdbqt";
            gpf = ligandDir + "/receptor_rigid.gpf";
            glg = ligandDir + "/receptor_rigid.glg";
            mapsPrefix = "receptor_rigid";
            vinaCommandParam = " --flex " + RECEPTOR_FLEX;
        }
        executeCommand(PYTHONSH + " " + VINA_SCRIPT + "/prepare_gpf.py -l " + ligandDir + "/" + ligandBaseName + ".pdbqt -r " + receptorToRun + " -y" + " -o " + gpf, new File(DOCKING_DIR));
        editGpfFile(gpf, configLigandTypes, configReceptor);
        executeCommand(AUTOGRID4 + " -p " + gpf + " -l " + glg, workingDirectory);
        File affinityFile = new File(ligandDir + "/vina_results.txt");
        executeCommand(VINA +
                " --ligand " + ligandDir + "/" + ligandBaseName + ".pdbqt --maps " + ligandDir + "/" + mapsPrefix + " --scoring ad4 --exhaustiveness " +
                EXHAUSTIVENESS + " --out " + ligandDir + "/" + ligandBaseName + "_out.pdbqt" + vinaCommandParam, null, affinityFile);
    }

    private static void generatePyMolScript() throws IOException {
        //open molecules
        //color protein by chain
        //find polar contact between ligand and receptor
        //display ligand as sticks
        //select pocket, byres all within 2.5 of sele
        //show pocket as sticks

        BufferedWriter writer = new BufferedWriter(new FileWriter(DOCKING_DIR + "/visualize_in_pymol.pml"));
        writer.write("load " + RECEPTOR_FILE_NAME + "\n");

        File baseDockingDirectory = new File(DOCKING_DIR);
        for (File ligandDirectory : Objects.requireNonNull(baseDockingDirectory.listFiles())) {
            if (ligandDirectory.isDirectory()) {
                for (File file : Objects.requireNonNull(ligandDirectory.listFiles())) {
                    if (file.getName().endsWith("_ad4_out.pdbqt")) {
                        String ligandOutName = file.getName().split("\\.")[0];
                        writer.write("load " + ligandDirectory.getPath() + "/" + file.getName() + ", " + ligandOutName + "\n");
                        writer.write("show dots, receptor\n");
                        writer.write("show sticks, " + ligandOutName + "\n");
                    }
                }
            }
        }

        writer.close();
    }

    private static void executeCommand(String command, File directory, File outputFile) throws IOException, InterruptedException {
        if (SHOW_COMMANDS) {
            System.out.println(command);
        }
        String[] cmdArray = {"/bin/bash", "-c", command};
        ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);

        if (directory != null) {
            processBuilder.directory(directory); // Set the working directory
        }

        if (outputFile != null) {
            processBuilder.redirectOutput(outputFile);
        }
        processBuilder.redirectErrorStream(true); // Merge stdout and stderr into the specified output file

        Process process = processBuilder.start();
        // Only read the output if you haven't redirected it to a file
        if (outputFile == null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        process.waitFor();
    }

    private static void executeCommand(String command, File directory) throws IOException, InterruptedException {
        executeCommand(command, directory, null);
    }

    private static void executeCommand(String command) throws IOException, InterruptedException {
        executeCommand(command, null);
    }

}