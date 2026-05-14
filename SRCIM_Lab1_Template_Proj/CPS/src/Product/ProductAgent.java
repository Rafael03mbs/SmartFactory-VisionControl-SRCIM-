package Product;

import Order.OrderAgent;
import Utilities.Constants;
import Utilities.DFInteraction;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.proto.AchieveREInitiator;
import jade.proto.ContractNetInitiator;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Vector;

/**
 * Product Agent — controls the execution process for a specific product.
 * Uses a SequentialBehaviour that dynamically chains SkillFSMs one at a time,
 * allowing the execution plan to be modified mid-flight (e.g., recovery after QC NOK).
 *
 * After each skill execution the product stays physically on the station.
 * The station is only released (via RELEASE message) when the product
 * is moved away by the AGV, or when the product finishes (takeDown).
 *
 * Lab 2: After Quality Check, if the result is NOK, recovery steps are
 * dynamically inserted into the execution plan (re-glue + re-inspect).
 * The defect region (TOP/BOTTOM) determines which glue station is used:
 *   TOP    → sk_g_b (only GlueStation1 has it)
 *   BOTTOM → sk_g_c (only GlueStation2 has it)
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class ProductAgent extends Agent {

    String id;
    ArrayList<String> executionPlan = new ArrayList<>();

    String currentLocation = "Source";
    long startTime;

    // The resource this product is currently physically sitting on.
    // Null initially (product is not on any station yet).
    AID occupiedResourceAID = null;

    // Lab 2: Last skill execution result (set by SkillFSM's execute handler)
    String lastExecutionResult = null;

    // Lab 2: Prevents infinite recovery loops — only one recovery attempt allowed
    boolean recoveryAttempted = false;

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id = (String) args[0];
        this.executionPlan = this.getExecutionList((String) args[1]);
        this.startTime = System.currentTimeMillis();
        System.out.println("Product launched: " + this.id + " Requires: " + executionPlan);

        // Build the execution chain dynamically: one skill at a time
        SequentialBehaviour mainPlan = new SequentialBehaviour();
        chainNextSkill(mainPlan, 0);
        addBehaviour(mainPlan);
    }

    /**
     * Dynamically adds the next skill FSM + a result checker to the SequentialBehaviour.
     * The result checker will call this method again for the following skill,
     * creating a chain reaction that processes the entire execution plan.
     * This allows the plan to be modified between skills (recovery insertion).
     */
    private void chainNextSkill(SequentialBehaviour seq, int index) {
        if (index >= executionPlan.size()) {
            // All skills done — add completion handler
            seq.addSubBehaviour(new OneShotBehaviour() {
                @Override
                public void action() {
                    long totalTime = System.currentTimeMillis() - startTime;
                    System.out.println("========================================");
                    System.out.println("DASHBOARD | Product " + id + " COMPLETED");
                    System.out.println("  Skills executed: " + executionPlan.size());
                    System.out.println("  Total time: " + totalTime + " ms");
                    System.out.println("  Final location: " + currentLocation);
                    System.out.println("  Recovery attempted: " + recoveryAttempted);
                    System.out.println("========================================");
                    myAgent.doDelete();
                }
            });
            return;
        }

        String skill = executionPlan.get(index);

        // Add the FSM for this skill
        seq.addSubBehaviour(new SkillFSM(skill, index + 1, executionPlan.size()));

        // Add a result checker that runs AFTER the skill FSM completes
        seq.addSubBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                // Lab 2: Check QC result and insert recovery if needed
                if (skill.equals(Constants.SK_QUALITY_CHECK)
                        && lastExecutionResult != null
                        && lastExecutionResult.startsWith("NOK")
                        && !recoveryAttempted) {

                    recoveryAttempted = true;
                    String defectRegion = lastExecutionResult.contains("#")
                            ? lastExecutionResult.split("#")[1]
                            : "TOP";

                    // Heuristic: TOP/LEFT defects → GlueStation1 (sk_g_b)
                    //            BOTTOM/RIGHT defects → GlueStation2 (sk_g_c)
                    String recoverySkill;
                    if ("TOP".equals(defectRegion) || "LEFT".equals(defectRegion)) {
                        recoverySkill = Constants.SK_GLUE_TYPE_B; // Only GlueStation1 has this
                    } else {
                        recoverySkill = Constants.SK_GLUE_TYPE_C; // Only GlueStation2 has this
                    }

                    // Insert recovery steps right after the current position
                    int insertPos = index + 1;
                    executionPlan.add(insertPos, recoverySkill);
                    executionPlan.add(insertPos + 1, Constants.SK_QUALITY_CHECK);
                    // sk_drop was at index+1, now shifted to index+3

                    System.out.println("╔══════════════════════════════════════╗");
                    System.out.println("║  RECOVERY | " + id + " is NOK!");
                    System.out.println("║  Defect region: " + defectRegion);
                    System.out.println("║  Recovery skill: " + recoverySkill);
                    System.out.println("║  Updated plan: " + executionPlan);
                    System.out.println("╚══════════════════════════════════════╝");
                } else if (skill.equals(Constants.SK_QUALITY_CHECK)
                        && lastExecutionResult != null) {
                    if (lastExecutionResult.startsWith("NOK") && recoveryAttempted) {
                        System.out.println("[" + id + "] 2nd QC still NOK — product will be removed regardless.");
                    } else {
                        System.out.println("[" + id + "] QC passed: " + lastExecutionResult);
                    }
                }

                // Chain the next skill
                chainNextSkill((SequentialBehaviour) getParent(), index + 1);
            }
        });
    }

    @Override
    protected void takeDown() {
        // Release the station we're currently on (if any)
        releaseOccupiedResource();
        OrderAgent.productFinished();
        System.out.println(id + " removed from system. WIP slot freed.");
        super.takeDown();
    }

    /**
     * Sends a RELEASE message to the currently occupied resource,
     * so it can accept new products.
     */
    private void releaseOccupiedResource() {
        if (occupiedResourceAID != null) {
            ACLMessage release = new ACLMessage(ACLMessage.INFORM);
            release.setOntology(Constants.ONTOLOGY_RELEASE_RESOURCE);
            release.addReceiver(occupiedResourceAID);
            send(release);
            // System.out.println(id + " released " + occupiedResourceAID.getLocalName());
            occupiedResourceAID = null;
        }
    }

    /**
     * FSMBehaviour for a single skill execution:
     * SEARCH_RES -> NEGOTIATE -> SEARCH_TRANS -> MOVE -> EXECUTE
     * (MOVE is skipped if already at the right location)
     *
     * If all resources REFUSE the CFP (all stations busy), the FSM
     * loops back from NEGOTIATE -> SEARCH_RES (transition 0) to retry.
     */
    private class SkillFSM extends jade.core.behaviours.FSMBehaviour {
        String skill;
        int stepNum;
        int totalSteps;

        DFAgentDescription[] availableResources;
        AID chosenResourceAID;
        String chosenResourceLocation;
        DFAgentDescription[] transportResources;

        // Retry counter: after too many failures, disable lookahead to break deadlocks
        int negotiationRetries = 0;
        static final int LOOKAHEAD_RETRY_LIMIT = 3;

        public SkillFSM(String skill, int stepNum, int totalSteps) {
            this.skill = skill;
            this.stepNum = stepNum;
            this.totalSteps = totalSteps;

            // Search for resources that can execute this skill
            Behaviour searchRes = new OneShotBehaviour() {
                @Override
                public void action() {
                    negotiationRetries++;
                    // System.out.println(id + " [Step " + stepNum + "/" + totalSteps + "] Processing skill: " + skill
                    //         + (negotiationRetries > 1 ? " (retry #" + negotiationRetries + ")" : ""));
                    try {
                        DFAgentDescription[] currentRes = DFInteraction.SearchInDFByName(skill, myAgent);
                        
                        // Lookahead to prevent deadlocks: if a resource provides BOTH the current and next skill, prefer it.
                        // BUT: after LOOKAHEAD_RETRY_LIMIT failures, disable it to avoid circular waits.
                        if (negotiationRetries <= LOOKAHEAD_RETRY_LIMIT && stepNum < executionPlan.size()) {
                            String nextSkill = executionPlan.get(stepNum);
                            DFAgentDescription[] nextRes = DFInteraction.SearchInDFByName(nextSkill, myAgent);
                            
                            ArrayList<DFAgentDescription> intersection = new ArrayList<>();
                            for (DFAgentDescription cr : currentRes) {
                                for (DFAgentDescription nr : nextRes) {
                                    if (cr.getName().equals(nr.getName())) {
                                        intersection.add(cr);
                                        break;
                                    }
                                }
                            }
                            
                            if (!intersection.isEmpty()) {
                                availableResources = intersection.toArray(new DFAgentDescription[0]);
                                return;
                            }
                        } else if (negotiationRetries > LOOKAHEAD_RETRY_LIMIT) {
                            // System.out.println(id + " LOOKAHEAD DISABLED for " + skill + " — negotiating with ALL resources");
                        }
                        
                        availableResources = currentRes;
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }
                }
            };

            // Negotiate with available resources using ContractNet
            ContractNetInitiator negotiate = new ContractNetInitiator(ProductAgent.this,
                    new ACLMessage(ACLMessage.CFP)) {

                // 0 = retry (all refused), 1 = needs transport, 2 = already at location
                int nextState = 0;

                @Override
                protected Vector prepareCfps(ACLMessage cfp) {
                    // On FSM reset JADE passes null — create a fresh message
                    if (cfp == null) {
                        cfp = new ACLMessage(ACLMessage.CFP);
                    }
                    // Reset for this new round
                    nextState = 0;
                    cfp.clearAllReceiver();
                    cfp.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
                    cfp.setContent(skill);
                    if (availableResources != null) {
                        for (DFAgentDescription dfd : availableResources) {
                            cfp.addReceiver(dfd.getName());
                        }
                    }
                    Vector<ACLMessage> v = new Vector<>();
                    v.add(cfp);
                    return v;
                }

                @Override
                protected void handleAllResponses(Vector responses, Vector acceptances) {
                    ACLMessage bestProposal = null;
                    int bestMetric = Integer.MAX_VALUE;

                    Enumeration e = responses.elements();
                    while (e.hasMoreElements()) {
                        ACLMessage response = (ACLMessage) e.nextElement();
                        if (response.getPerformative() == ACLMessage.PROPOSE) {
                            int metric = Integer.parseInt(response.getContent());
                            if (metric < bestMetric) {
                                bestMetric = metric;
                                bestProposal = response;
                            }
                        }
                    }

                    if (bestProposal != null) {
                        // We got at least one proposal — accept the best, reject the rest
                        Enumeration e2 = responses.elements();
                        while (e2.hasMoreElements()) {
                            ACLMessage response = (ACLMessage) e2.nextElement();
                            if (response.getPerformative() == ACLMessage.PROPOSE) {
                                ACLMessage reply = response.createReply();
                                if (response.getSender().equals(bestProposal.getSender())) {
                                    reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                                    // System.out.println(id + " accepted proposal from "
                                    //         + response.getSender().getLocalName() + " (metric " + bestMetric + ")");
                                } else {
                                    reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                                }
                                acceptances.addElement(reply);
                            }
                        }
                    } else {
                        // ── ALL resources refused ── wait and retry
                        // System.out.println(id + " ALL stations busy for skill " + skill + " — will retry in 1s.");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                        nextState = 0; // loop back to SEARCH_RES
                    }
                }

                @Override
                protected void handleInform(ACLMessage inform) {
                    chosenResourceAID = inform.getSender();
                    chosenResourceLocation = inform.getContent();

                    if (!currentLocation.equals(chosenResourceLocation)) {
                        nextState = 1; // Needs transport
                    } else {
                        nextState = 2; // Already at location, skip move
                    }
                }

                @Override
                public int onEnd() {
                    return nextState;
                }
            };

            // Search for transport agent
            Behaviour searchTrans = new OneShotBehaviour() {
                @Override
                public void action() {
                    try {
                        transportResources = DFInteraction.SearchInDFByName(Constants.SK_MOVE, myAgent);
                    } catch (FIPAException e) {
                        e.printStackTrace();
                    }
                }
            };

            // Request move from transport agent
            AchieveREInitiator move = new AchieveREInitiator(ProductAgent.this, new ACLMessage(ACLMessage.REQUEST)) {
                @Override
                protected Vector prepareRequests(ACLMessage req) {
                    req.setOntology(Constants.ONTOLOGY_MOVE);
                    if (transportResources != null && transportResources.length > 0) {
                        req.addReceiver(transportResources[0].getName());
                    }
                    req.setContent(currentLocation + Constants.TOKEN + chosenResourceLocation);

                    Vector<ACLMessage> v = new Vector<>();
                    v.add(req);
                    return v;
                }

                @Override
                protected void handleInform(ACLMessage inform) {
                    // AGV has moved us — release the OLD station we were sitting on
                    releaseOccupiedResource();
                    currentLocation = chosenResourceLocation;
                    // We will set occupiedResourceAID after skill execution
                }
            };

            // Request skill execution from chosen resource
            AchieveREInitiator execute = new AchieveREInitiator(ProductAgent.this, new ACLMessage(ACLMessage.REQUEST)) {
                @Override
                protected Vector prepareRequests(ACLMessage req) {
                    req.setOntology(Constants.ONTOLOGY_EXECUTE_SKILL);
                    req.addReceiver(chosenResourceAID);
                    req.setContent(skill);

                    Vector<ACLMessage> v = new Vector<>();
                    v.add(req);
                    return v;
                }

                @Override
                protected void handleInform(ACLMessage inform) {
                    // Store the result for the result checker to process
                    lastExecutionResult = inform.getContent();
                    // Skill done — we are now physically on this station
                    occupiedResourceAID = chosenResourceAID;
                    // System.out.println(id + " skill " + skill + " completed. Result: "
                    //         + lastExecutionResult + " | Occupying " + chosenResourceAID.getLocalName());
                }
            };

            // FSM state registration
            registerFirstState(searchRes, "SEARCH_RES");
            registerState(negotiate, "NEGOTIATE");
            registerState(searchTrans, "SEARCH_TRANS");
            registerState(move, "MOVE");
            registerLastState(execute, "EXECUTE");

            // FSM transitions
            registerDefaultTransition("SEARCH_RES", "NEGOTIATE");
            // Transition 0: all refused → retry from SEARCH_RES (reset the ContractNet)
            registerTransition("NEGOTIATE", "SEARCH_RES", 0, new String[]{"SEARCH_RES", "NEGOTIATE"});
            // Transition 1: needs transport
            registerTransition("NEGOTIATE", "SEARCH_TRANS", 1);
            // Transition 2: already at location
            registerTransition("NEGOTIATE", "EXECUTE", 2);
            registerDefaultTransition("SEARCH_TRANS", "MOVE");
            registerDefaultTransition("MOVE", "EXECUTE");
        }
    }

    private ArrayList<String> getExecutionList(String productType) {
        switch (productType) {
            case "A":
                return new ArrayList<>(Constants.PROD_A);
            case "B":
                return new ArrayList<>(Constants.PROD_B);
            case "C":
                return new ArrayList<>(Constants.PROD_C);
        }
        return new ArrayList<>();
    }
}
