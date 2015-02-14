package jalse;

import static jalse.misc.JALSEExceptions.AGENT_LIMIT_REARCHED;
import static jalse.misc.JALSEExceptions.CLUSTER_ALREADY_ASSOCIATED;
import static jalse.misc.JALSEExceptions.CLUSTER_LIMIT_REARCHED;
import static jalse.misc.JALSEExceptions.throwRE;
import jalse.actions.Action;
import jalse.actions.Scheduler;
import jalse.agents.Agent;
import jalse.agents.Agents;
import jalse.listeners.ClusterListener;
import jalse.listeners.ListenerSet;
import jalse.misc.JALSEExceptions;
import jalse.tags.Tag;
import jalse.tags.TagSet;
import jalse.tags.Taggable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JALSE is the overall container and engine for each simulation. It provides
 * the ability to create a number of {@link Cluster} and execute {@link Action}
 * at given intervals. Although {@link Cluster} and {@link Agent} can be
 * created/killed no {@link Action} will run until {@link Engine#tick()} is
 * called.
 *
 * @author Elliot Ford
 *
 */
public class JALSE extends Engine implements Taggable, Scheduler<JALSE> {

    /**
     * An {@link AtomicInteger} implementation with defensive
     * increment/decrement functionality. When doing so if the predicate is met
     * the supplied exception will be thrown.
     *
     */
    protected class DefensiveAtomicInteger extends AtomicInteger {

	private static final long serialVersionUID = 5685665215057250307L;

	private final Predicate<Integer> predicate;
	private final Supplier<? extends RuntimeException> supplier;

	/**
	 * Creates a new instance of DefensiveAtomicInteger.
	 *
	 * @param predicate
	 *            The test to pass to avoid the supplied exception being
	 *            thrown.
	 * @param supplier
	 *            Exception supplier for when predicate is not met.
	 */
	public DefensiveAtomicInteger(final Predicate<Integer> predicate,
		final Supplier<? extends RuntimeException> supplier) {

	    this.predicate = predicate;
	    this.supplier = supplier;
	}

	private void testPossibleAdd(final int i) {

	    if (predicate.test(get() + i)) {

		throwRE(supplier);
	    }
	}

	/**
	 * Decrements the value of the integer. Throws possible exception if the
	 * predicate is not met.
	 *
	 */
	public synchronized void defensiveIncrement() {

	    testPossibleAdd(1);
	    incrementAndGet();
	}

	/**
	 * Increments the value of the integer. Throws possible exception if the
	 * predicate is not met.
	 *
	 */
	public synchronized void defensiveDecrement() {

	    testPossibleAdd(-1);
	    decrementAndGet();
	}
    }

    /**
     * Current state information.
     */
    protected final TagSet tags;

    private final DefensiveAtomicInteger agentCount;
    private final int agentLimit;
    private final DefensiveAtomicInteger clusterCount;
    private final int clusterLimit;
    private final ListenerSet<ClusterListener> clusterListeners;
    private final Map<UUID, Cluster> clusters;

    /**
     * Creates a new instance of JALSE with the given qualities.
     *
     * @param tps
     *            Number of ticks per second the engine should tick at.
     * @param totalThreads
     *            Total number of threads the engine should use.
     * @param clusterLimit
     *            Maximum number of clusters.
     * @param agentLimit
     *            Maximum number of agents.
     * @throws IllegalArgumentException
     *             All parameters must be above zero.
     */
    public JALSE(final int tps, final int totalThreads, final int clusterLimit, final int agentLimit) {

	super(tps, totalThreads);

	if (clusterLimit <= 0 || agentLimit <= 0) {

	    throw new IllegalArgumentException();
	}

	this.clusterLimit = clusterLimit;
	this.agentLimit = agentLimit;

	agentCount = new DefensiveAtomicInteger(i -> i > agentLimit, AGENT_LIMIT_REARCHED);
	clusterCount = new DefensiveAtomicInteger(i -> i > clusterLimit, CLUSTER_LIMIT_REARCHED);

	clusters = new ConcurrentHashMap<>();
	clusterListeners = new ListenerSet<>(ClusterListener.class);

	tags = new TagSet();
    }

    /**
     * Adds a listener for clusters.
     *
     * @param listener
     *            Listener to add.
     *
     * @return {@code true} if JALSE did not already contain this listener.
     * @throws NullPointerException
     *             if listener is null.
     *
     * @see ListenerSet#add(Object)
     *
     */
    public boolean addClusterListener(final ClusterListener listener) {

	return clusterListeners.add(listener);
    }

    /**
     * Gets the total number of agents.
     *
     * @return Total number of agents within all clusters.
     */
    public int getAgentCount() {

	return agentCount.get();
    }

    /**
     * Gets the total agent count to manipulate when creating/killing agents.
     *
     * @return Total agent count.
     */
    protected DefensiveAtomicInteger getAgentCount0() {

	return agentCount;
    }

    /**
     * Gets the total agent limit.
     *
     * @return Total agent limit JALSE was initialised with.
     */
    public int getAgentLimit() {

	return agentLimit;
    }

    /**
     * Gets all the agents from each cluster.
     *
     * @return All agents in the simulation.
     */
    public Set<UUID> getAgentIDs() {

	return streamClusters().flatMap(c -> c.getAgentIDs().stream()).collect(Collectors.toSet());
    }

    /**
     * Provides a stream of all the agents from each cluster.
     *
     * @return A stream of all agents in the simulation.
     */
    public Stream<Agent> streamAgents() {

	return streamClusters().flatMap(c -> c.streamAgents());
    }

    /**
     * Provides a stream of all the agents from each cluster. These agents are
     * wrapped with the supplied agent type.
     *
     * @param type
     *            Agent type to wrap to.
     * @return A stream of all the agents in the simulation wrapped to the
     *         specified agent type.
     * @throws NullPointerException
     *             If the agent type is null.
     *
     * @see Agents#asType(Agent, Class)
     */
    public <T extends Agent> Stream<T> streamAgentsOfType(final Class<T> type) {

	return streamClusters().flatMap(c -> c.streamAgentsOfType(type));
    }

    /**
     * Gets the cluster with the specified ID.
     *
     * @param id
     *            Unique ID of the cluster.
     * @return Gets an Optional of the resulting cluster or an empty Optional if
     *         it was not found.
     * @throws NullPointerException
     *             If the ID is null.
     */
    public Optional<Cluster> getCluster(final UUID id) {

	return Optional.ofNullable(clusters.get(Objects.requireNonNull(id)));
    }

    /**
     * Gets the current total number of clusters.
     *
     * @return Total number of clusters within the simulation.
     */
    public int getClusterCount() {

	return clusterCount.get();
    }

    /**
     * Gets the total cluster limit.
     *
     * @return Total cluster limit JALSE was initialised with.
     */
    public int getClusterLimit() {

	return clusterLimit;
    }

    /**
     * Gets all the cluster listeners.
     *
     * @return All the cluster listeners.
     */
    public Set<ClusterListener> getClusterListeners() {

	return Collections.unmodifiableSet(clusterListeners);
    }

    /**
     * Gets the unique identifiers of all clusters.
     *
     * @return IDs of all clusters.
     */
    public Set<UUID> getClusterIDs() {

	return Collections.unmodifiableSet(clusters.keySet());
    }

    /**
     * Gets a stream of all clusters.
     *
     * @return A stream of all clusters.
     */
    public Stream<Cluster> streamClusters() {

	return clusters.values().stream();
    }

    /**
     * Gets all of the clusters within JALSE.
     *
     * @return All of the clusters.
     */
    public Set<Cluster> getClusters() {

	return streamClusters().collect(Collectors.toSet());
    }

    /**
     * Gets the first action run each tick.
     *
     * @return First action to be run or null if none set.
     */
    @SuppressWarnings("unchecked")
    public Action<JALSE> getFirstAction() {

	return (Action<JALSE>) super.getFirstAction();
    }

    /**
     * Gets the last action run each tick.
     *
     * @return Last action to be run or null if none set.
     */
    @SuppressWarnings("unchecked")
    public Action<JALSE> getLastAction() {

	return (Action<JALSE>) super.getLastAction();
    }

    /**
     * Gets whether the first action of tick a has been set.
     *
     * @return Whether first action has been set.
     *
     * @see #getFirstAction()
     * @see #setFirstAction(Action)
     */
    public boolean hasFirstAction() {

	return super.getFirstAction() != null;
    }

    /**
     * Gets whether the last action of a tick has been set.
     *
     * @return Whether last action has been set.
     *
     * @see #getLastAction()
     * @see #setLastAction(Action)
     */
    public boolean hasLastAction() {

	return super.getLastAction() != null;
    }

    /**
     * Kills the cluster with the specified id.
     *
     * @param id
     *            ID of the cluster.
     * @return Whether the cluster was alive.
     *
     * @see Cluster#isAlive()
     */
    public boolean killCluster(final UUID id) {

	Cluster killed;

	if ((killed = clusters.remove(id)) != null) {

	    clusterCount.defensiveDecrement();

	    killed.cancelTasks();

	    clusterListeners.getProxy().clusterCreated(killed);
	}

	return killed != null;
    }

    /**
     * Creates a new cluster with a random ID.
     *
     * @return The newly created cluster.
     *
     * @see UUID#randomUUID()
     * @see JALSEExceptions#CLUSTER_LIMIT_REARCHED
     */
    public Cluster newCluster() {

	return newCluster(UUID.randomUUID());
    }

    /**
     * Creates new cluster with the given ID.
     *
     * @param id
     *            Cluster ID.
     * @return The newly created cluster.
     * @throws IllegalStateException
     *             If the cluster limit has been reached.
     * @throws IllegalArgumentException
     *             If the cluster ID is already assigned.
     *
     * @see JALSEExceptions#CLUSTER_LIMIT_REARCHED
     * @see JALSEExceptions#CLUSTER_ALREADY_ASSOCIATED
     */
    public Cluster newCluster(final UUID id) {

	clusterCount.defensiveIncrement();

	final Cluster cluster;

	if (clusters.putIfAbsent(id, cluster = new Cluster(this, id)) != null) {

	    throwRE(CLUSTER_ALREADY_ASSOCIATED);
	}

	clusterListeners.getProxy().clusterCreated(cluster);

	return cluster;
    }

    /**
     * Removes a cluster listener.
     *
     * @param listener
     *            Listener to remove.
     *
     * @return {@code true} if the listener was removed.
     * @throws NullPointerException
     *             if listener is null.
     *
     * @see ListenerSet#remove(Object)
     *
     */
    public boolean removeClusterListener(final ClusterListener listener) {

	return clusterListeners.remove(listener);
    }

    @Override
    public UUID schedule(final Action<JALSE> action, final long initialDelay, final long period, final TimeUnit unit) {

	return schedule(action, this, initialDelay, period, unit);
    }

    /**
     * Sets the first action of a tick to be run.
     *
     * @param action
     *            Action to set.
     *
     */
    public void setFirstAction(final Action<JALSE> action) {

	setFirstAction(action, this);
    }

    /**
     * Sets the last action of a tick to be run.
     *
     * @param action
     *            Action to set.
     *
     */
    public void setLastAction(final Action<JALSE> action) {

	setLastAction(action, this);
    }

    @Override
    public Set<Tag> getTags() {

	return Collections.unmodifiableSet(tags);
    }

    /**
     * Checks whether the cluster is alive within the engine.
     * 
     * @param id
     *            ID of cluster.
     * @return {@code true} if the engine contains the cluster and {@code false}
     *         if it does not.
     */
    public boolean isClusterAlive(UUID id) {

	return clusters.containsKey(id);
    }
}
