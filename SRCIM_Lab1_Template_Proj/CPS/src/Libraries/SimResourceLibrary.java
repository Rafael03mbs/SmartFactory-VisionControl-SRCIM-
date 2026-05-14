/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package Libraries;

import coppelia.CharWA;
import coppelia.IntW;
import coppelia.remoteApi;
import jade.core.Agent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class SimResourceLibrary implements IResource {

    public remoteApi sim;
    public int clientID = -1;
    Agent myAgent;
    final long timeout = 30000;

    // Lab 2: Inspection API endpoint
    private static final String INSPECTION_API_URL = "http://localhost:8000/inspect";

    @Override
    public void init(Agent a) {
        this.myAgent = a;
        sim = new remoteApi();
        int port = 0;
        switch (myAgent.getLocalName()) {
            case "GlueStation1":
                port = 19997;
                break;
            case "GlueStation2":
                port = 19998;
                break;
            case "QualityControlStation1":
                port = 19999;
                break;
            case "QualityControlStation2":
                port = 20000;
                break;
            case "Operator":
                port = 20001;
                break;
        }
        clientID = sim.simxStart("127.0.0.1", port, true, true, 5000, 5);
        if (clientID != -1) {
            System.out
                    .println(this.myAgent.getAID().getLocalName() + " initialized communication with the simulation.");
            // Clear any stale signals from a previous session
            sim.simxClearStringSignal(clientID, myAgent.getLocalName(), sim.simx_opmode_blocking);
            sim.simxClearIntegerSignal(clientID, myAgent.getLocalName(), sim.simx_opmode_blocking);
        }
    }

    @Override
    public String[] getSkills() {
        String[] skills;
        switch (myAgent.getLocalName()) {
            case "GlueStation1":
                skills = new String[2];
                skills[0] = Utilities.Constants.SK_GLUE_TYPE_A;
                skills[1] = Utilities.Constants.SK_GLUE_TYPE_B;
                return skills;
            case "GlueStation2":
                skills = new String[2];
                skills[0] = Utilities.Constants.SK_GLUE_TYPE_A;
                skills[1] = Utilities.Constants.SK_GLUE_TYPE_C;
                return skills;
            case "QualityControlStation1":
                skills = new String[1];
                skills[0] = Utilities.Constants.SK_QUALITY_CHECK;
                return skills;
            case "QualityControlStation2":
                skills = new String[1];
                skills[0] = Utilities.Constants.SK_QUALITY_CHECK;
                return skills;
            case "Operator":
                skills = new String[2];
                skills[0] = Utilities.Constants.SK_PICK_UP;
                skills[1] = Utilities.Constants.SK_DROP;
                return skills;
        }
        return null;
    }

    @Override
    public String executeSkill(String skillID) {
        // Step 1: Send skill command to CoppeliaSim and wait for completion
        sim.simxSetStringSignal(clientID, myAgent.getLocalName(), new CharWA(skillID), sim.simx_opmode_blocking);
        IntW opRes = new IntW(-1);
        long startTime = System.currentTimeMillis();
        while ((opRes.getValue() != 1) && (System.currentTimeMillis() - startTime < timeout)) {
            sim.simxGetIntegerSignal(clientID, myAgent.getLocalName(), opRes, sim.simx_opmode_blocking);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Logger.getLogger(SimResourceLibrary.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        sim.simxClearIntegerSignal(clientID, myAgent.getLocalName(), sim.simx_opmode_blocking);

        if (opRes.getValue() != 1) {
            return null; // Skill execution failed (timeout)
        }

        // Step 2: For Quality Check, call the AI Inspection API
        if (skillID.equals(Utilities.Constants.SK_QUALITY_CHECK)) {
            return callInspectionAPI();
        }

        return "done";
    }

    /**
     * Lab 2: Calls the FastAPI inspection service with the captured image.
     * The simulation saves images to the "images" folder with the station name.
     * Returns "OK" or "NOK#TOP" / "NOK#BOTTOM" based on defect position.
     */
    private String callInspectionAPI() {
        String stationName = myAgent.getLocalName();
        // The simulation saves the QC image to SRCIM/images/<StationName>.jpg
        // Use absolute path to avoid working-directory issues
        Path imagePath = Paths.get("C:\\Users\\rafae\\Desktop\\SRCIM\\images\\" + stationName + ".jpg");

        if (!Files.exists(imagePath)) {
            System.out.println("[QC API] WARNING: Image not found at " + imagePath.toAbsolutePath()
                    + " — defaulting to OK");
            return "OK";
        }

        try {
            System.out.println("[QC API] " + stationName + " sending image to inspection API...");
            String response = sendMultipartRequest(imagePath.toFile());
            System.out.println("[QC API] " + stationName + " API response: " + response);
            return parseInspectionResponse(response);
        } catch (Exception e) {
            System.out.println("[QC API] ERROR calling inspection API: " + e.getMessage());
            e.printStackTrace();
            // On API failure, default to OK to avoid blocking production
            return "OK";
        }
    }

    /**
     * Sends the image file to the FastAPI /inspect endpoint via multipart/form-data POST.
     */
    private String sendMultipartRequest(File imageFile) throws IOException {
        String boundary = "----JavaBoundary" + System.currentTimeMillis();
        URL url = new URL(INSPECTION_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);

        try (OutputStream os = connection.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true)) {

            // File part
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(imageFile.getName()).append("\"\r\n");
            writer.append("Content-Type: image/jpeg\r\n\r\n");
            writer.flush();

            Files.copy(imageFile.toPath(), os);
            os.flush();

            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }

        // Read response
        int responseCode = connection.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode < 300)
                ? connection.getInputStream()
                : connection.getErrorStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        connection.disconnect();
        return response.toString();
    }

    /**
     * Parses the JSON response from the inspection API.
     * Expected format: {"status": "OK"} or {"status": "NOK", "defect_region": "TOP", ...}
     */
    private String parseInspectionResponse(String json) {
        // Simple JSON parsing without external libraries
        String status = extractJsonValue(json, "status");

        if ("OK".equals(status)) {
            return "OK";
        }

        if ("NOK".equals(status)) {
            String region = extractJsonValue(json, "defect_region");
            if (region != null && !region.isEmpty()) {
                return "NOK#" + region;
            }
            return "NOK#TOP"; // Default if region is missing
        }

        // Check for errors
        String error = extractJsonValue(json, "error");
        if (error != null) {
            System.out.println("[QC API] API returned error: " + error);
        }

        return "OK"; // Default on unknown response
    }

    /**
     * Extracts a simple string value from JSON by key name.
     * Works for flat JSON objects with string values.
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int colonIndex = json.indexOf(":", keyIndex + searchKey.length());
        if (colonIndex == -1) return null;

        // Find the value (skip whitespace and quotes)
        int valueStart = -1;
        boolean inQuotes = false;
        for (int i = colonIndex + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                if (!inQuotes) {
                    inQuotes = true;
                    valueStart = i + 1;
                } else {
                    return json.substring(valueStart, i);
                }
            }
        }
        return null;
    }
}
