package Transport;

import Utilities.Constants;
import Utilities.DFInteraction;
import jade.core.Agent;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREResponder;

import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import Libraries.ITransport;

/**
 * Transport Agent — abstracts the AGV.
 * Registers the Move skill in the DF and handles transport requests.
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class TransportAgent extends Agent {

    String id;
    ITransport myLib;
    String description;
    String[] associatedSkills;

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id = (String) args[0];
        this.description = (String) args[1];

        try {
            String className = "Libraries." + (String) args[2];
            Class cls = Class.forName(className);
            Object instance;
            instance = cls.newInstance();
            myLib = (ITransport) instance;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.getLogger(TransportAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        myLib.init(this);
        this.associatedSkills = myLib.getSkills();
        System.out.println("Transport Deployed: " + this.id + " Executes: " + Arrays.toString(associatedSkills));

        // Register Move skill in the DF
        try {
            DFInteraction.RegisterInDF(this, associatedSkills, Constants.DFSERVICE_TRANSPORT);
        } catch (FIPAException ex) {
            Logger.getLogger(TransportAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        // AchieveRE Responder for move requests
        MessageTemplate moveTemplate = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchOntology(Constants.ONTOLOGY_MOVE));

        addBehaviour(new AchieveREResponder(this, moveTemplate) {
            @Override
            protected ACLMessage prepareResultNotification(ACLMessage request, ACLMessage response)
                    throws FailureException {
                String content = request.getContent();
                // Parse "origin#TOKEN#destination"
                StringTokenizer st = new StringTokenizer(content, Constants.TOKEN);
                String origin = st.nextToken();
                String destination = st.nextToken();
                String productID = request.getSender().getLocalName();

                // System.out
                //         .println(id + " received move request for " + productID + ": " + origin + " -> " + destination);
                boolean success = myLib.executeMove(origin, destination, productID);

                ACLMessage reply = request.createReply();
                if (success) {
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("moved");
                    // System.out.println(id + " completed move for " + productID + ": " + origin + " -> " + destination);
                } else {
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent("Move failed");
                }
                return reply;
            }
        });
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }
}
