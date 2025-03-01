package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.Collaborator;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.collaborator.CollaboratorPayload;
import burp.api.montoya.BurpExtension;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.UserInterface;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;

import static burp.api.montoya.collaborator.SecretKey.secretKey;

public class Collabfiltrator implements BurpExtension {
    private Logging logging;
    private UserInterface userInterface;
    private CollaboratorClient collaboratorClient;
    private Map<String, String> lastExfiltratedTableByDBMS = new HashMap<>();
    private Map<String, String> lastExfiltratedColumnByDBMS = new HashMap<>();
    private RCEPayloadManager rcePayloadManager;
    private SQLiPayloadManager sqliPayloadManager;
    private RCEMonitoringManager rceMonitoringManager;
    private SQLiMonitoringManager sqliMonitoringManager;

    // GUI Components
    private JPanel mainPanel;
    private RCEPanel rcePanel;
    private SQLiPanel sqliPanel;

    @Override
    public void initialize(MontoyaApi api) {
        this.logging = api.logging();
        this.userInterface = api.userInterface();
        this.collaboratorClient = createCollaboratorClient(api.collaborator(), api.persistence().extensionData());
        this.rcePayloadManager = new RCEPayloadManager(collaboratorClient, logging);
        this.sqliPayloadManager = new SQLiPayloadManager(lastExfiltratedTableByDBMS, lastExfiltratedColumnByDBMS, logging);

        // Register unloading handler
        api.extension().registerUnloadingHandler(() -> {
            logging.logToOutput("Collabfiltrator extension unloading - cleaning up resources...");
            // Force stop monitoring
            rceMonitoringManager.stopMonitoring();
            sqliMonitoringManager.stopMonitoring();
            logging.logToOutput("Collabfiltrator extension cleanup completed.");
        });

        logging.logToOutput("Extension Name: Collabfiltrator");
        logging.logToOutput("Description:    Exfiltrate Blind RCE and SQLi output over DNS via Burp Collaborator.");
        logging.logToOutput("Human Authors:  Adam Logue, Frank Scarpella, Jared McLaren, Ryan Griffin");
        logging.logToOutput("AI Authors:     ChatGPT 4o, Claude 3.5 Sonnet");
        logging.logToOutput("Version:        4.0");

        setupGui();
        addTabToBurpSuite();
        
        // Initialize monitoring managers after GUI setup
        this.rceMonitoringManager = new RCEMonitoringManager(collaboratorClient, logging, rcePanel);
        this.sqliMonitoringManager = new SQLiMonitoringManager(collaboratorClient, logging, sqliPanel, 
                                                             lastExfiltratedTableByDBMS, 
                                                             lastExfiltratedColumnByDBMS);
        
        generateNewRCECollaboratorPayload();
        generateNewSQLiCollaboratorPayload();
    }

    private CollaboratorClient createCollaboratorClient(Collaborator collaborator, PersistedObject persistedData) {
        String persistedCollaboratorKey = "persisted_collaborator";
        CollaboratorClient collaboratorClient;

        String existingCollaboratorKey = persistedData.getString(persistedCollaboratorKey);

        if (existingCollaboratorKey != null) {
            logging.logToOutput("Restoring Collaborator client from key.");
            collaboratorClient = collaborator.restoreClient(secretKey(existingCollaboratorKey));
        } else {
            logging.logToOutput("No previously found Collaborator client. Creating new client...");
            collaboratorClient = collaborator.createClient();

            // Save the secret key of the CollaboratorClient so that you can retrieve it later.
            logging.logToOutput("Saving Collaborator secret key.");
            persistedData.setString(persistedCollaboratorKey, collaboratorClient.getSecretKey().toString());
        }

        return collaboratorClient;
    }

    private void setupGui() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        
        // Create the tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();

        // Create panels
        rcePanel = new RCEPanel(this);
        sqliPanel = new SQLiPanel(this);

        // Add tabs to the tabbed pane
        tabbedPane.addTab("RCE", rcePanel);
        tabbedPane.addTab("SQLi", sqliPanel);

        // Add the tabbed pane to the main panel
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
    }

    private void addTabToBurpSuite() {
        userInterface.registerSuiteTab("Collabfiltrator", mainPanel);
    }

    private void generateNewRCECollaboratorPayload() {
        CollaboratorPayload payload = collaboratorClient.generatePayload();
        rcePanel.getRceBurpCollaboratorDomainTxt().setText(payload.toString());
    }

    private void generateNewSQLiCollaboratorPayload() {
        CollaboratorPayload payload = collaboratorClient.generatePayload();
        sqliPanel.getSqliBurpCollaboratorDomainTxt().setText(payload.toString());
    }

    public void copyToClipboard(String payload) {
        StringSelection selection = new StringSelection(payload);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }

    public void stopMonitoringAction() {
        rceMonitoringManager.stopMonitoring();
        sqliMonitoringManager.stopMonitoring();
        rcePanel.getRceProgressBar().setIndeterminate(false);
        sqliPanel.getSqliProgressBar().setIndeterminate(false);
    }

    public void executeRCEPayload(String command) {
        CollaboratorPayload payload = collaboratorClient.generatePayload();
        rcePanel.getRceBurpCollaboratorDomainTxt().setText(payload.toString());
                
        rcePanel.getRceProgressBar().setIndeterminate(true);
        rcePanel.getRceStopButton().setVisible(true);
                
        String osType = (String) rcePanel.getOsComboBox().getSelectedItem();
        String generatedPayload = rcePayloadManager.createPayload(osType, command, payload.toString());
        
        rcePanel.getRcePayloadTxt().setText(generatedPayload);
        rceMonitoringManager.startMonitoring(payload.toString());
    }

    public void generateSQLiPayload() {
        String dbms = (String) sqliPanel.getDbmsComboBox().getSelectedItem();
        String extractType = (String) sqliPanel.getExtractComboBox().getSelectedItem();
        boolean hexEncoded = sqliPanel.getHexEncodingToggle().isSelected();

        CollaboratorPayload payload = collaboratorClient.generatePayload();
        sqliPanel.getSqliBurpCollaboratorDomainTxt().setText(payload.toString());
        
        String generatedPayload = sqliPayloadManager.generatePayload(dbms, extractType, hexEncoded, 
                                                   payload.toString());

        sqliPanel.getSqlipayloadTxt().setText(generatedPayload);
        sqliMonitoringManager.startMonitoring(payload.toString());
    }
}