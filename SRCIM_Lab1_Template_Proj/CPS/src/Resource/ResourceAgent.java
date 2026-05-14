package Resource;

import Utilities.Constants;
import Utilities.DFInteraction;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREResponder;
import jade.proto.ContractNetResponder;

import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import Libraries.IResource;

/**
 * Resource Agent — abstracts physical resources (glue stations, quality
 * control, operator). Registers skills in the DF and handles negotiation
 * and execution requests.
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class ResourceAgent extends Agent {

    String id;
    IResource myLib;
    String description;
    String[] associatedSkills;
    String location;

    // Queue size used for load balancing during negotiation
    int queueSize = 0;

    // Tracks physical occupation: true when an item is on this station.
    // Stays true from proposal acceptance until the product sends an
    // explicit RELEASE after being moved away by the AGV.
    volatile boolean occupied = false;

    // Which product agent is currently occupying this station.
    // The occupant is allowed to re-negotiate (e.g. sk_g_a then sk_g_b
    // on the same GlueStation without moving away).
    volatile String occupiedBy = null;

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
            myLib = (IResource) instance;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.getLogger(ResourceAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        this.location = (String) args[3];

        myLib.init(this);
        this.associatedSkills = myLib.getSkills();
        System.out.println("Resource Deployed: " + this.id + " Executes: " + Arrays.toString(associatedSkills));

        // Register each skill as a service in the DF
        try {
            DFInteraction.RegisterInDF(this, associatedSkills, Constants.DFSERVICE_RESOURCE);
        } catch (FIPAException ex) {
            Logger.getLogger(ResourceAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        // ── ContractNet Responder for negotiation ──
        MessageTemplate negotiateTemplate = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.CFP),
                MessageTemplate.MatchOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE));

        addBehaviour(new ContractNetResponder(this, negotiateTemplate) {
            @Override
            protected ACLMessage handleCfp(ACLMessage cfp)
                    throws RefuseException, FailureException, NotUnderstoodException {

                String requester = cfp.getSender().getLocalName();

                // GUARD: refuse if occupied by a DIFFERENT product
                if (occupied && !requester.equals(occupiedBy)) {
                    System.out.println(id + " is OCCUPIED by " + occupiedBy
                            + " — refusing CFP from " + requester + " for " + cfp.getContent());
                    throw new RefuseException("Station " + id + " is occupied by " + occupiedBy);
                }

                ACLMessage reply = cfp.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                // Metric based on queue size — lower is better, idle resources win
                Random rand = new Random();
                int metric = queueSize * 10 + rand.nextInt(10);
                reply.setContent(String.valueOf(metric));
                System.out.println(id + " proposing with metric: " + metric
                        + " (queue: " + queueSize + ", requester: " + requester + ")");
                return reply;
            }

            @Override
            protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
                    throws FailureException {
                ACLMessage reply = accept.createReply();
                reply.setPerformative(ACLMessage.INFORM);
                reply.setContent(location);
                queueSize++;
                occupied = true;
                occupiedBy = accept.getSender().getLocalName();
                System.out.println(id + " proposal accepted by " + occupiedBy
                        + ". Location: " + location + " (now OCCUPIED)");
                return reply;
            }

            @Override
            protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
                System.out.println(id + " proposal was rejected.");
            }
        });

        // ── AchieveRE Responder for skill execution ──
        // NOTE: occupied stays TRUE after execution — item is still on station.
        // It will be released only when the product sends a RELEASE message.
        MessageTemplate executeTemplate = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchOntology(Constants.ONTOLOGY_EXECUTE_SKILL));

        addBehaviour(new AchieveREResponder(this, executeTemplate) {
            @Override
            protected ACLMessage prepareResultNotification(ACLMessage request, ACLMessage response)
                    throws FailureException {
                String skillId = request.getContent();
                System.out.println(id + " executing skill: " + skillId);
                String result = myLib.executeSkill(skillId);
                ACLMessage reply = request.createReply();
                if (result != null) {
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent(result); // "done", "OK", or "NOK#REGION"
                    System.out.println(id + " finished executing skill: " + skillId + " | result: " + result);
                } else {
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent("Skill execution failed: " + skillId);
                    System.out.println(id + " FAILED executing skill: " + skillId);
                }
                queueSize = Math.max(0, queueSize - 1);
                // DO NOT set occupied=false here — item is still physically on the station.
                return reply;
            }
        });

        // ── Release listener — product sends this when it has left the station ──
        MessageTemplate releaseTemplate = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchOntology(Constants.ONTOLOGY_RELEASE_RESOURCE));

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(releaseTemplate);
                if (msg != null) {
                    String who = msg.getSender().getLocalName();
                    System.out.println(id + " RELEASED by " + who + " — station is now FREE.");
                    occupied = false;
                    occupiedBy = null;
                } else {
                    block();
                }
            }
        });
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }
}
