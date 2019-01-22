package it.unimi.di.se.monitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;

import com.google.inject.Injector;

import it.unimi.di.se.decision.DecisionMaker;
import it.unimi.di.se.mdp.MdpDslStandaloneSetup;
import it.unimi.di.se.mdp.mdpDsl.Arc;
import it.unimi.di.se.mdp.mdpDsl.ConcentrationParam;
import it.unimi.di.se.mdp.mdpDsl.DirichletPrior;
import it.unimi.di.se.mdp.mdpDsl.MDPModel;
import it.unimi.di.se.mdp.mdpDsl.ObservableMap;
import it.unimi.di.se.mdp.mdpDsl.State;
import jmarkov.jmdp.CharAction;


public class Monitor {
	
	enum Termination {
		COVERAGE,
		CONVERGENCE,
		LIMIT
	}
	
	private static final Logger log = LoggerFactory.getLogger(Monitor.class.getName());
	
	private MDPModel model = null;
	State currentState = null;
	private long currentTime;
	private LinkedBlockingQueue<Event> queue = null;
	
	private HashMap<State, ArrayList<Arc>> outgoingArcs = new HashMap<>();
	private HashMap<Arc, ObservableMap> arcsMapping = new HashMap<>();
	
	// Bayesian analysis fields
	private HashMap<State, Dirichlet> prior = new HashMap<>();
	private HashMap<State, Dirichlet> posterior = new HashMap<>();
	private HashMap<State, Integer> stateIndex = new HashMap<>();
	
	private DecisionMaker decisionMaker = null;
	
	private Coverage coverageInfo = null;
	
	public Monitor(DecisionMaker decisionMaker){
		Injector injector = new MdpDslStandaloneSetup().createInjectorAndDoEMFRegistration();
		XtextResourceSet resourceSet = injector.getInstance(XtextResourceSet.class);
		resourceSet.addLoadOption(XtextResource.OPTION_RESOLVE_ALL, Boolean.TRUE);
		URI uri = URI.createURI("dummy:/model.mdp");
		Resource resource = resourceSet.createResource(uri);
		
		InputStream in = null;
		try {
			in = new FileInputStream(new File(EventHandler.MODEL_PATH));
			resource.load(in, resourceSet.getLoadOptions());
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		this.decisionMaker = decisionMaker;
		
		// init MDP model
		model = (MDPModel) resource.getContents().get(0);
		retrieveOutgoingArcs();
		retrieveMapping();
		queue = new LinkedBlockingQueue<Event>();
		
		// coverage info
		coverageInfo = new Coverage(model);
		
		int i = 0;
		for(State s: model.getStates())
			stateIndex.put(s, i++);
		
		// init Baesyan analysis
		for(State s: model.getStates()){
			if(s.getPrior() != null && s.getPrior().size() > 0){
				for(DirichletPrior srcModelPrior: s.getPrior()) {
					Dirichlet dirichlePrior = new Dirichlet(model.getStates().size(), srcModelPrior.getAct());
					Dirichlet dirichlePosterior = new Dirichlet(model.getStates().size(), srcModelPrior.getAct());
					for(ConcentrationParam c: srcModelPrior.getConcentration()) {
						dirichlePrior.set(stateIndex.get(c.getDst()), Double.parseDouble(c.getAlpha()));
						dirichlePosterior.set(stateIndex.get(c.getDst()), Double.parseDouble(c.getAlpha()));
					}
					prior.put(s, dirichlePrior);
					posterior.put(s, dirichlePosterior);
					decisionMaker.updateDistance(stateIndex.get(s), dirichlePosterior.getDistance());
				}
			}
		}
	}
	
//	private Arc retrieveArc(State source, State target){
//		for(Arc a: outgoingArcs.get(source))
//			if(a.getDst().equals(target))
//				return a;
//		return null;
//	}
	
	private void retrieveOutgoingArcs(){
		for(Arc a: model.getArcs())
			addArc(a.getSrc(), a);
	}
	
	private void retrieveMapping(){
		for(ObservableMap m: model.getObservableActions())
			arcsMapping.put(m.getArc(), m);
	}
	
	private void addArc(State s, Arc a){
		if(outgoingArcs.containsKey(s))
			outgoingArcs.get(s).add(a);
		else {
			ArrayList<Arc> list = new ArrayList<Arc>();
			list.add(a);
			outgoingArcs.put(s, list);
		}
	}
	
	public DecisionMaker getDecisionMaker() {
		return decisionMaker;
	}
	
	public void setInitialState(){
		for(State s: model.getStates())
			if(s.isInitial()){
				currentState = s;
				break;
			}
		currentTime = System.currentTimeMillis();
	}
	
	public void launch(){
		Thread t = new Thread(new Runnable() {
	        @Override
	        public void run() {
		        	Monitor.this.startMonitor();
	        }
	    });
	    t.start();
	}
	
	private void startMonitor() {
		setInitialState();
		log.info("MONITOR STARTED...");
		while (true) {
			try {
				Event event = queue.take();
				if (event.isStop()) {
					log.info("MONITOR STOPPED...");
					report();
					System.exit(0);
				} else if (event.isReset()) {
					setInitialState();
				} else if (event.isReadState()) {
					CheckPoint.getInstance().join(Thread.currentThread(), currentState.getName());
				} else if (!checkEvent(event)) {
					try {
						throw new Exception("Invalid event: " + event.getName());
					} catch (Exception e) {
						e.printStackTrace();
						log.info("Current state: ");
						log.info(currentState.getName());

						log.info("**** TEST FAILED ****");
						log.info("TRACE:\n");
						System.exit(1);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private int showInferenceInfo = 0;
	private int eventCount = 0;
	
	private boolean checkEvent(Event event) {
		//long time = event.getTime() - currentTime;
		//log.info("[Monitor] checking event: " + event.getName() + ", time: " + time);
		eventCount++;
		for(Arc a: outgoingArcs.get(currentState))
			if(a.getName().equals(event.getName())){
				
				// update count if currentState is uncertain
				decisionMaker.updateCount(stateIndex.get(currentState));
				
				// Bayesian analysis and termination
				if(posterior.containsKey(currentState)) {
					posterior.get(currentState).update(stateIndex.get(a.getDst()));
					boolean convergence = true;
					boolean testConvergence = true;
					
					if(showInferenceInfo++ > 10) {
						for(State s: posterior.keySet())
							log.warn(s.getName() + " - " + posterior.get(s).report() + " events = " + eventCount);
						showInferenceInfo = 0;
					}
					
					for(State s: posterior.keySet()) {
						log.info("[Monitor] count = " + posterior.get(s).getCount() + ", sample = " + posterior.get(s).getSampleSize());
						testConvergence &= posterior.get(s).getCount() > EventHandler.SAMPLE_SIZE;
					}				
					if(testConvergence) {
						for(State s: posterior.keySet()) {
							log.info("[Monitor] PDF = " + posterior.get(s).pdf());
							posterior.get(s).resetCount();
							convergence &= posterior.get(s).convergence();
						}
						if(EventHandler.TERMINATION_CONDITION == Termination.CONVERGENCE && convergence) {
							log.info("[Monitor] convergence reached.");
							addEvent(Event.stopEvent());
						}
					}
				}
				
				// coverage info and termination
				coverageInfo.addExecution(stateIndex.get(currentState), new CharAction(a.getAct().getName().charAt(0)));
				int tests = 0;
				for(State s: posterior.keySet()) {
					tests += posterior.get(s).getSampleSize();
					decisionMaker.updateDistance(stateIndex.get(s), posterior.get(s).getDistance());
				}
				if(tests % EventHandler.SAMPLE_SIZE >= EventHandler.SAMPLE_SIZE-1) {
					log.warn(coverageInfo.toString());
					if(EventHandler.TERMINATION_CONDITION == Termination.COVERAGE && coverageInfo.getCoverage() >= EventHandler.COVERAGE) {
						log.info("[Monitor] convergence reached.");
						addEvent(Event.stopEvent());
					}
					else if(EventHandler.TERMINATION_CONDITION == Termination.LIMIT && tests >= EventHandler.LIMIT-1) {
						log.info("[Monitor] #test limit reached.");
						addEvent(Event.stopEvent());
					}
				}
				
				// update state
				currentState = a.getDst();
				log.info("Set current state: " + currentState.getName());
				currentTime = event.getTime();
				return true;
			}
		return false;
	}
    
	public void addEvent(Event event){
		try {
			queue.put(event);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public void report() {
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.out.println("\n********* Monitor report *********\n");
		System.out.println("Uncertain MDP parameters:");
		for(State s: prior.keySet()) {
			System.out.println(s.getName() + ":=");
			System.out.println("    Action: " + prior.get(s).action());
			System.out.println("    Prior: " + prior.get(s).printParams() + " --> Posterior: " + posterior.get(s).printParams());
			System.out.println("    #test: " + posterior.get(s).getSampleSize());
			//System.out.println("    Pr(D|M): " + posterior.get(s).pdf());
			System.out.println("    Mode x_i: " + posterior.get(s).printMode());
			System.out.println("    Mean E[x_i]: " + posterior.get(s).printMean());
			System.out.println("    95% HPD region: " + Arrays.deepToString(posterior.get(s).hpdRegion(0.95)));
			System.out.println("    HPD region size: " + posterior.get(s).getDistance());
		}
	}
	
	public static class CheckPoint {
		
		private Thread waitingThread = null;
		private static CheckPoint instance = null;
		private String state = null;
		
		private CheckPoint() { }
		
		public static CheckPoint getInstance() {
			if(instance == null) {
				synchronized (CheckPoint.class) {
					if(instance == null)
						instance = new CheckPoint();
				}
			}
			return instance;
		}
		
		public synchronized void join(Thread executingThread, String state) {
			this.state = state;
			if(waitingThread != null) {
				waitingThread = null;
				notifyAll();
			}
			else {
				waitingThread = executingThread;
				try {
					wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		
		public synchronized String join(Thread executingThread) {
			if(waitingThread != null) {
				waitingThread = null;
				notifyAll();
			}
			else {
				waitingThread = executingThread;
				try {
					wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			return state;
		}
	}
}
