package Libraries;

import jade.core.Agent;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class TestResourceLibrary implements IResource {

    private Agent myAgent;
    private final Random random = new Random();

    @Override
    public void init(Agent myAgent) {
        this.myAgent = myAgent;
        System.out.println("Test library has been successfully initialized for agent: " + myAgent.getLocalName());
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
        try {
            switch (skillID) {
                case Utilities.Constants.SK_GLUE_TYPE_A: {
                    Thread.sleep(2000);
                    return "done";
                }
                case Utilities.Constants.SK_GLUE_TYPE_B: {
                    Thread.sleep(3000);
                    return "done";
                }
                case Utilities.Constants.SK_GLUE_TYPE_C: {
                    Thread.sleep(4000);
                    return "done";
                }                
                case Utilities.Constants.SK_PICK_UP:
                    Thread.sleep(1000);
                    return "done";
                case Utilities.Constants.SK_DROP:
                    Thread.sleep(1000);
                    return "done";
                case Utilities.Constants.SK_QUALITY_CHECK: {
                    Thread.sleep(2000);
                    // Lab 2: Simulate random OK/NOK for testing (30% chance of defect)
                    if (random.nextInt(100) < 30) {
                        String region = random.nextBoolean() ? "TOP" : "BOTTOM";
                        System.out.println("[TEST QC] " + myAgent.getLocalName() 
                                + " -> NOK (defect region: " + region + ")");
                        return "NOK#" + region;
                    }
                    System.out.println("[TEST QC] " + myAgent.getLocalName() + " -> OK");
                    return "OK";
                }
            }
        } catch (InterruptedException ex) {
            Logger.getLogger(TestLibrary.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }
}
