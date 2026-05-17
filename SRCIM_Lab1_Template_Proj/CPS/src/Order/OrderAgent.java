package Order;

import Product.ProductAgent;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Order Agent — receives production orders and launches products
 * using a balanced sequence with WIP control.
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class OrderAgent extends Agent {

    int productA;
    int productB;
    int productC;

    // Max simultaneous products in the system
    private static final int WIP_LIMIT = 4;

    private static int activeProducts = 0;
    private static int productCounter = 0;
    private static final Object lock = new Object();

    /** Called by ProductAgent when it finishes. */
    public static void productFinished() {
        synchronized (lock) {
            activeProducts = Math.max(0, activeProducts - 1);
        }
    }

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.productA = parseProductCount(args, 0);
        this.productB = parseProductCount(args, 1);
        this.productC = parseProductCount(args, 2);

        System.out.println("Order Received " + " ProductsA " + productA +
                " ProductsB " + productB + " ProductsC " + productC);

        List<String> sequence = generateBalancedSequence(productA, productB, productC);

        System.out.println("========================================");
        System.out.println("ORDER DASHBOARD | Production Sequence:");
        System.out.println("  Total products: " + sequence.size());
        System.out.println("  Sequence: " + sequence);
        System.out.println("  WIP Limit: " + WIP_LIMIT);
        System.out.println("========================================");

        addBehaviour(new WIPControlledLauncher(this, sequence));
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }

    private int parseProductCount(Object[] args, int index) {
        if (args == null || args.length <= index || args[index] == null) {
            return 0;
        }
        Object value = args[index];
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    /**
     * Launches products respecting the WIP limit to avoid overloading stations.
     */
    private class WIPControlledLauncher extends Behaviour {
        private final List<String> sequence;
        private int nextIndex = 0;
        private boolean finished = false;

        WIPControlledLauncher(Agent a, List<String> sequence) {
            super(a);
            this.sequence = sequence;
        }

        @Override
        public void action() {
            if (nextIndex >= sequence.size()) {
                finished = true;
                System.out.println("OrderAgent: All " + sequence.size() + " products have been launched.");
                return;
            }

            boolean launched = false;
            synchronized (lock) {
                if (activeProducts < WIP_LIMIT) {
                    String productType = sequence.get(nextIndex);
                    try {
                        launchProduct(productType);
                        activeProducts++;
                        nextIndex++;
                        launched = true;
                    } catch (StaleProxyException e) {
                        e.printStackTrace();
                    }
                }
            }

            if (!launched) {
                block(500);
            } else {
                block(300);
            }
        }

        @Override
        public boolean done() {
            return finished;
        }
    }

    private void launchProduct(String productType) throws StaleProxyException {
        ProductAgent newProduct = new ProductAgent();
        String id = "Product" + productCounter;
        newProduct.setArguments(new Object[] { id, productType });
        AgentController agent = this.getContainerController().acceptNewAgent(id, newProduct);
        agent.start();
        System.out.println("Launched " + id + " (Type " + productType + ") [Active: " + (activeProducts + 1) + "/" + WIP_LIMIT + "]");
        productCounter++;
    }

    /**
     * Generates a balanced sequence avoiding more than 2 consecutive
     * launches of the same variant when possible.
     */
    private List<String> generateBalancedSequence(int a, int b, int c) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (a > 0)
            counts.put("A", a);
        if (b > 0)
            counts.put("B", b);
        if (c > 0)
            counts.put("C", c);

        String last1 = null;
        String last2 = null;

        while (!counts.isEmpty()) {
            String bestChoice = null;
            int maxCount = -1;

            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String variant = entry.getKey();
                int count = entry.getValue();

                // Skip if it would cause 3 consecutive and alternatives exist
                if (variant.equals(last1) && variant.equals(last2) && counts.size() > 1) {
                    continue;
                }

                if (count > maxCount) {
                    maxCount = count;
                    bestChoice = variant;
                }
            }

            if (bestChoice == null) {
                for (String variant : counts.keySet()) {
                    bestChoice = variant;
                    break;
                }
            }

            result.add(bestChoice);

            int remaining = counts.get(bestChoice) - 1;
            if (remaining <= 0) {
                counts.remove(bestChoice);
            } else {
                counts.put(bestChoice, remaining);
            }

            last2 = last1;
            last1 = bestChoice;
        }

        return result;
    }
}
