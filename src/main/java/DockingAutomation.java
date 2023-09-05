import java.io.*;
import java.util.*;

public class DockingAutomation {
    private static final String RECEPTOR_FILE_NAME = "/Users/adr/localGoogleDrive/CYFIP2_project/lab_journals/Denis_Reshetov/files/20230904/7usc_R87C_H_pymol.pdb";
    private static final String BASE_DIR = "/Users/adr/localGoogleDrive/CYFIP2_project/lab_journals/Denis_Reshetov/files/20230905/docking";
    private static final String LIGANDS_DIR = BASE_DIR + "/ligands_input";
    private static final String DOCKING_DIR = BASE_DIR + "/7usc_rigid";
    private static final String PREPARE_RECEPTOR = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/prepare_receptor";
    private static final String VINA_SCRIPT = "/Users/adr/localGoogleDrive/CYFIP2_project/lab_journals/Denis_Reshetov/files/20230904/AutoDock-Vina/example/autodock_scripts";
    private static final String OBABEL = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/obabel";
    private static final String PYTHONSH = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/pythonsh";
    private static final String AUTOGRID4 = "/Users/adr/software/ADFRsuite_x86_64Darwin_1.0/bin/bin/autogrid4";
    private static final String VINA = "/Users/adr/software/vina_1.2.5_mac_x86_64";
    private static final int EXHAUSTIVENESS = 32;

    public static void main(String[] args) {
        try {
            prepareReceptor();

            File ligandsDirectory = new File(LIGANDS_DIR);
            for (File file : Objects.requireNonNull(ligandsDirectory.listFiles())) {
                if (file.getName().endsWith(".sdf")) {
                    processLigand(file.getName());
                }
            }
//
//            generatePyMolScript();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void editGpfFile(String path) throws IOException {
        List<String> modifiedLines = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("gridcenter")) {
                    modifiedLines.add("gridcenter 85.176 141.059 163.344");
                } else {
                    modifiedLines.add(line);
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
        new File(DOCKING_DIR).mkdirs();

        File receptorFile = new File(DOCKING_DIR + "/7usc_R87C.pdbqt");
        if (!receptorFile.exists()) {  // Check if the receptor has already been prepared
            executeCommand("cp " + RECEPTOR_FILE_NAME + " " + DOCKING_DIR);
            executeCommand(PREPARE_RECEPTOR + " -r " + DOCKING_DIR + "/7usc_R87C_H_pymol.pdb -o " + DOCKING_DIR + "/7usc_R87C.pdbqt");
        } else {
            System.out.println("Receptor is already prepared. Skipping preparation...");
        }
    }

    private static void processLigand(String ligandFileName) throws IOException, InterruptedException {
        String ligandBaseName = ligandFileName.split("\\.")[0];
        String ligandDir = DOCKING_DIR + "/" + ligandBaseName;
        new File(ligandDir).mkdirs();

        String receptor_pbqt = DOCKING_DIR + "/7usc_R87C.pdbqt";
        executeCommand("cp " + receptor_pbqt + " " + ligandDir);

        executeCommand(OBABEL + " " + LIGANDS_DIR + "/" + ligandFileName + " -O " + ligandDir + "/" + ligandBaseName + "_hyd.sdf -h");
        executeCommand("/usr/local/bin/python3 /Library/Frameworks/Python.framework/Versions/3.6/bin/mk_prepare_ligand.py -i " + ligandDir + "/" + ligandBaseName + "_hyd.sdf -o " + ligandDir + "/" + ligandBaseName + ".pdbqt");
        File workingDirectory = new File(ligandDir);
        executeCommand(PYTHONSH + " " + VINA_SCRIPT + "/prepare_gpf.py -l " + ligandDir + "/" + ligandBaseName + ".pdbqt -r " + receptor_pbqt + " -y", workingDirectory);
        editGpfFile(ligandDir + "/7usc_R87C.gpf");
        executeCommand(AUTOGRID4 + " -p " + ligandDir + "/7usc_R87C.gpf -l " + ligandDir + "/7usc_R87C.glg", workingDirectory);
        File affinityFile = new File(ligandDir+"/vina_results.txt");
        executeCommand(VINA + " --ligand " + ligandDir + "/" + ligandBaseName + ".pdbqt --maps " + ligandDir + "/7usc_R87C --scoring ad4 --exhaustiveness " +
                EXHAUSTIVENESS +
                " --out " + ligandDir + "/" + ligandBaseName + "_ad4_out.pdbqt", null, affinityFile);
    }

    private static void generatePyMolScript() throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(DOCKING_DIR + "/visualize_in_pymol.pml"));
        writer.write("load " + RECEPTOR_FILE_NAME + "\n");

        File baseDockingDirectory = new File(DOCKING_DIR);
        for (File ligandDirectory : Objects.requireNonNull(baseDockingDirectory.listFiles())) {
            if (ligandDirectory.isDirectory()) {
                for (File file : Objects.requireNonNull(ligandDirectory.listFiles())) {
                    if (file.getName().endsWith("_ad4_out.pdbqt")) {
                        String ligandOutName = file.getName().split("\\.")[0];
                        writer.write("load " + ligandDirectory.getPath() + "/" + file.getName() + ", " + ligandOutName + "\n");
                        writer.write("show dots, 7usc_R87C_H_pymol\n");
                        writer.write("show sticks, " + ligandOutName + "\n");
                    }
                }
            }
        }

        writer.close();
    }

    private static void executeCommand(String command, File directory, File outputFile) throws IOException, InterruptedException {
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