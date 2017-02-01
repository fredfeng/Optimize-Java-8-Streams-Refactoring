package edu.cuny.hunter.streamrefactoring.core.analysis;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.BaseStream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStatusContext;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.objenesis.instantiator.ObjectInstantiator;
import org.osgi.framework.FrameworkUtil;

import com.ibm.safe.controller.ISafeSolver;
import com.ibm.safe.internal.exceptions.MaxFindingsException;
import com.ibm.safe.internal.exceptions.PropertiesException;
import com.ibm.safe.internal.exceptions.SetUpException;
import com.ibm.safe.internal.exceptions.SolverTimeoutException;
import com.ibm.safe.properties.PropertiesManager;
import com.ibm.safe.rules.TypestateRule;
import com.ibm.safe.typestate.core.BenignOracle;
import com.ibm.safe.typestate.core.TypestateSolverFactory;
import com.ibm.safe.typestate.options.TypeStateOptions;
import com.ibm.safe.typestate.rules.ITypeStateDFA;
import com.ibm.wala.analysis.typeInference.TypeAbstraction;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.cast.java.ipa.callgraph.JavaSourceAnalysisScope;
import com.ibm.wala.cast.java.translator.jdt.JDTIdentityMapper;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.PhiValue;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.Value;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.strings.StringStuff;

import edu.cuny.hunter.streamrefactoring.core.safe.EventTrackingTypeStateProperty;
import edu.cuny.hunter.streamrefactoring.core.utils.Util;
import edu.cuny.hunter.streamrefactoring.core.wala.EclipseProjectAnalysisEngine;

/**
 * An abstract notion of a stream in memory.
 * 
 * @author <a href="mailto:raffi.khatchadourian@hunter.cuny.edu">Raffi
 *         Khatchadourian</a>
 *
 */
@SuppressWarnings("restriction")
public class Stream {
	private static Map<IJavaProject, IClassHierarchy> javaProjectToClassHierarchyMap = new HashMap<>();

	private static Map<IJavaProject, EclipseProjectAnalysisEngine<InstanceKey>> javaProjectToAnalysisEngineMap = new HashMap<>();

	private static final Logger logger = Logger.getLogger("edu.cuny.hunter.streamrefactoring");

	private static Map<MethodDeclaration, IR> methodDeclarationToIRMap = new HashMap<>();

	private static Objenesis objenesis = new ObjenesisStd();

	private static final String PLUGIN_ID = FrameworkUtil.getBundle(Stream.class).getSymbolicName();

	public static void clearCaches() {
		javaProjectToClassHierarchyMap.clear();
		javaProjectToAnalysisEngineMap.clear();
		methodDeclarationToIRMap.clear();
	}

	private static Object createInstance(Class<?> clazz) throws NoninstantiablePossibleStreamSourceException {
		try {
			return clazz.newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			ObjectInstantiator<?> instantiator = objenesis.getInstantiatorOf(clazz);
			try {
				return instantiator.newInstance();
			} catch (InstantiationError e2) {
				throw new NoninstantiablePossibleStreamSourceException(
						clazz + " cannot be instantiated: " + e2.getCause(), e2, clazz);
			}
		}
	}

	private static String getBinaryName(TypeReference typeReference) {
		TypeName name = typeReference.getName();
		String slashToDot = StringStuff.slashToDot(name.getPackage().toString() + "." + name.getClassName().toString());
		return slashToDot;
	}

	private static int getLineNumberFromAST(SimpleName methodName) {
		CompilationUnit compilationUnit = (CompilationUnit) ASTNodes.getParent(methodName, ASTNode.COMPILATION_UNIT);
		int lineNumberFromAST = compilationUnit.getLineNumber(methodName.getStartPosition());
		return lineNumberFromAST;
	}

	private static int getLineNumberFromIR(IBytecodeMethod method, SSAInstruction instruction)
			throws InvalidClassFileException {
		int bytecodeIndex = method.getBytecodeIndex(instruction.iindex);
		int lineNumberFromIR = method.getLineNumber(bytecodeIndex);
		return lineNumberFromIR;
	}

	private static String getMethodIdentifier(IMethodBinding methodBinding) {
		IMethod method = (IMethod) methodBinding.getJavaElement();

		String methodIdentifier = null;
		try {
			methodIdentifier = Util.getMethodIdentifier(method);
		} catch (JavaModelException e) {
			throw new RuntimeException(e);
		}
		return methodIdentifier;
	}

	private static Set<TypeAbstraction> getPossibleTypes(int valueNumber, TypeInference inference) {
		Set<TypeAbstraction> ret = new HashSet<>();
		Value value = inference.getIR().getSymbolTable().getValue(valueNumber);

		if (value instanceof PhiValue) {
			// multiple possible types.
			PhiValue phiValue = (PhiValue) value;
			SSAPhiInstruction phiInstruction = phiValue.getPhiInstruction();
			int numberOfUses = phiInstruction.getNumberOfUses();
			// get the possible types for each use.
			for (int i = 0; i < numberOfUses; i++) {
				int use = phiInstruction.getUse(i);
				Set<TypeAbstraction> possibleTypes = getPossibleTypes(use, inference);
				ret.addAll(possibleTypes);
			}
		} else {
			// one one possible type.
			ret.add(inference.getType(valueNumber));
		}

		return ret;
	}

	private static Spliterator<?> getSpliterator(Object instance, IMethod calledMethod)
			throws CannotExtractSpliteratorException {
		Spliterator<?> spliterator = null;

		if (instance instanceof Iterable) {
			spliterator = ((Iterable<?>) instance).spliterator();
		} else {
			// try to call the stream() method to get the spliterator.
			BaseStream<?, ?> baseStream = null;
			try {
				Method streamCreationMethod = instance.getClass().getMethod(calledMethod.getElementName());
				Object stream = streamCreationMethod.invoke(instance);

				if (stream instanceof BaseStream) {
					baseStream = (BaseStream<?, ?>) stream;
					spliterator = baseStream.spliterator();
				} else
					throw new CannotExtractSpliteratorException(
							"Returned object of type: " + stream.getClass() + " doesn't implement BaseStream.",
							stream.getClass());
			} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException e) {
				throw new CannotExtractSpliteratorException(
						"Cannot extract the spliterator from object of type: " + instance.getClass(), e,
						instance.getClass());
			} finally {
				if (baseStream != null)
					baseStream.close();
			}
		}
		return spliterator;
	}

	private int getUseValueNumberForCreation() throws InvalidClassFileException, IOException, CoreException {
		return getInstructionForCreation().map(i -> i.getUse(0)).orElse(-1);
	}

	private Optional<SSAInvokeInstruction> getInstructionForCreation()
			throws InvalidClassFileException, IOException, CoreException {
		IBytecodeMethod method = (IBytecodeMethod) this.getEnclosingMethodIR().getMethod();
		SimpleName methodName = this.getCreation().getName();

		for (Iterator<SSAInstruction> it = this.getEnclosingMethodIR().iterateNormalInstructions(); it.hasNext();) {
			SSAInstruction instruction = it.next();
			System.out.println(instruction);

			int lineNumberFromIR = getLineNumberFromIR(method, instruction);
			int lineNumberFromAST = getLineNumberFromAST(methodName);

			if (lineNumberFromIR == lineNumberFromAST) {
				// lines from the AST and the IR match. Let's dive a little
				// deeper to be more confident of the correspondence.
				if (instruction.hasDef() && instruction.getNumberOfDefs() == 2) {
					if (instruction instanceof SSAInvokeInstruction) {
						SSAInvokeInstruction invokeInstruction = (SSAInvokeInstruction) instruction;
						TypeReference declaredTargetDeclaringClass = invokeInstruction.getDeclaredTarget()
								.getDeclaringClass();
						if (getBinaryName(declaredTargetDeclaringClass)
								.equals(this.getCreation().getExpression().resolveTypeBinding().getBinaryName())) {
							MethodReference callSiteDeclaredTarget = invokeInstruction.getCallSite()
									.getDeclaredTarget();
							// FIXME: This matching needs much work.
							if (callSiteDeclaredTarget.getName().toString()
									.equals(this.getCreation().resolveMethodBinding().getName())) {
								return Optional.of(invokeInstruction);
							}
						}
					} else
						logger.warning("Instruction: " + instruction + " is not an SSAInstruction.");
				} else
					logger.warning("Instruction: " + instruction + " has no definitions.");
			}
		}
		return Optional.empty();
	}

	private static StreamOrdering inferStreamOrdering(Set<TypeAbstraction> possibleStreamSourceTypes,
			IMethod calledMethod)
			throws InconsistentPossibleStreamSourceOrderingException, NoniterablePossibleStreamSourceException,
			NoninstantiablePossibleStreamSourceException, CannotExtractSpliteratorException {
		StreamOrdering ret = null;

		for (TypeAbstraction typeAbstraction : possibleStreamSourceTypes) {
			if (typeAbstraction != TypeAbstraction.TOP) {
				StreamOrdering ordering = inferStreamOrdering(typeAbstraction, calledMethod);

				if (ret == null)
					ret = ordering;
				else if (ret != ordering)
					throw new InconsistentPossibleStreamSourceOrderingException(
							ret + " does not match " + ordering + " for type: " + typeAbstraction + ".");
			}
		}

		return ret;
	}

	// TODO: Cache this?
	private static StreamOrdering inferStreamOrdering(String className, IMethod calledMethod)
			throws NoniterablePossibleStreamSourceException, NoninstantiablePossibleStreamSourceException,
			CannotExtractSpliteratorException {
		try {
			Class<?> clazz = Class.forName(className);

			// is it instantiable?
			if (!isAbstractType(clazz)) {
				Object instance = createInstance(clazz);
				boolean ordered;

				Spliterator<?> spliterator = getSpliterator(instance, calledMethod);
				ordered = spliterator.hasCharacteristics(Spliterator.ORDERED);
				// TODO: Can we use something other than reflection,
				// like static analysis? Also, it may be an abstract
				// class.

				// FIXME: What if there is something under this that is
				// ordered? I guess this applies to both intra and
				// interprocedural analysis but more for the former.
				if (!ordered)
					return StreamOrdering.UNORDERED;
				else
					return StreamOrdering.ORDERED;
			} else
				throw new NoninstantiablePossibleStreamSourceException(
						clazz + " cannot be instantiated because it is an interface.", clazz);
		} catch (ClassNotFoundException e) {
			// TODO Not sure what we should do in this situation. What if we
			// can't instantiate the iterable? Is there another way to find out
			// this information? This could be a problem in third-party
			// container libraries. Also, what if we don't have the class in the
			// classpath?
			e.printStackTrace();
			throw new RuntimeException("Can't find: " + className, e);
		}
	}

	private static StreamOrdering inferStreamOrdering(TypeAbstraction typeAbstraction, IMethod calledMethod)
			throws NoniterablePossibleStreamSourceException, NoninstantiablePossibleStreamSourceException,
			CannotExtractSpliteratorException {
		TypeReference typeReference = typeAbstraction.getTypeReference();
		String binaryName = getBinaryName(typeReference);

		return inferStreamOrdering(binaryName, calledMethod);
	}

	private static boolean isAbstractType(Class<?> clazz) {
		// if it's an interface.
		if (clazz.isInterface())
			return true; // can't instantiate an interface.
		else if (Modifier.isAbstract(clazz.getModifiers()))
			return true; // can't instantiate an abstract type.
		else
			return false;
	}

	private final MethodInvocation creation;

	private final MethodDeclaration enclosingMethodDeclaration;

	private StreamExecutionMode executionMode;

	private StreamOrdering ordering;

	private RefactoringStatus status = new RefactoringStatus();

	public Stream(MethodInvocation streamCreation)
			throws ClassHierarchyException, IOException, CoreException, InvalidClassFileException {
		this.creation = streamCreation;
		this.enclosingMethodDeclaration = (MethodDeclaration) ASTNodes.getParent(this.getCreation(),
				ASTNode.METHOD_DECLARATION);
		this.inferExecution();
		try {
			this.inferOrdering();
		} catch (InconsistentPossibleStreamSourceOrderingException e) {
			logger.log(Level.WARNING, "Exception caught while processing: " + streamCreation, e);
			addStatusEntry(streamCreation, PreconditionFailure.INCONSISTENT_POSSIBLE_STREAM_SOURCE_ORDERING,
					"Stream: " + streamCreation + " has inconsistent possible source orderings.");
		} catch (NoniterablePossibleStreamSourceException e) {
			logger.log(Level.WARNING, "Exception caught while processing: " + streamCreation, e);
			addStatusEntry(streamCreation, PreconditionFailure.NON_ITERABLE_POSSIBLE_STREAM_SOURCE,
					"Stream: " + streamCreation + " has a non-iterable possible source.");
		} catch (NoninstantiablePossibleStreamSourceException e) {
			logger.log(Level.WARNING, "Exception caught while processing: " + streamCreation, e);
			addStatusEntry(streamCreation, PreconditionFailure.NON_INSTANTIABLE_POSSIBLE_STREAM_SOURCE, "Stream: "
					+ streamCreation + " has a non-instantiable possible source with type: " + e.getSourceType() + ".");
		} catch (CannotExtractSpliteratorException e) {
			logger.log(Level.WARNING, "Exception caught while processing: " + streamCreation, e);
			addStatusEntry(streamCreation, PreconditionFailure.NON_DETERMINABLE_STREAM_SOURCE_ORDERING,
					"Cannot extract spliterator from type: " + e.getFromType() + " for stream: " + streamCreation
							+ ".");
		} catch (PropertiesException | CancelException e) {
			logger.log(Level.SEVERE, "Error while building stream.", e);
			throw new RuntimeException(e);
		}
	}

	private void addStatusEntry(MethodInvocation streamCreation, PreconditionFailure failure, String message) {
		CompilationUnit compilationUnit = (CompilationUnit) ASTNodes.getParent(streamCreation,
				ASTNode.COMPILATION_UNIT);
		ICompilationUnit compilationUnit2 = (ICompilationUnit) compilationUnit.getJavaElement();
		RefactoringStatusContext context = JavaStatusContext.create(compilationUnit2, streamCreation);
		this.getStatus().addEntry(RefactoringStatus.ERROR, message, context, PLUGIN_ID, failure.getCode(), this);
	}

	private IClassHierarchy getClassHierarchy() throws IOException, CoreException {
		IJavaProject javaProject = getCreationJavaProject();
		IClassHierarchy classHierarchy = javaProjectToClassHierarchyMap.get(javaProject);
		if (classHierarchy == null) {
			EclipseProjectAnalysisEngine<InstanceKey> engine = getAnalysisEngine();
			engine.buildAnalysisScope();
			classHierarchy = engine.buildClassHierarchy();

			if (classHierarchy != null)
				javaProjectToClassHierarchyMap.put(javaProject, classHierarchy);
		}

		return classHierarchy;
	}

	private EclipseProjectAnalysisEngine<InstanceKey> getAnalysisEngine() throws IOException, CoreException {
		IJavaProject javaProject = this.getCreationJavaProject();
		EclipseProjectAnalysisEngine<InstanceKey> engine = javaProjectToAnalysisEngineMap.get(javaProject);
		if (engine == null) {
			engine = new EclipseProjectAnalysisEngine<InstanceKey>(javaProject);

			if (engine != null)
				javaProjectToAnalysisEngineMap.put(javaProject, engine);
		}
		return engine;
	}

	private IJavaProject getCreationJavaProject() {
		return this.getCreation().resolveMethodBinding().getJavaElement().getJavaProject();
	}

	public MethodInvocation getCreation() {
		return creation;
	}

	public IMethod getEnclosingMethod() {
		return (IMethod) getEnclosingMethodDeclaration().resolveBinding().getJavaElement();
	}

	public MethodDeclaration getEnclosingMethodDeclaration() {
		return enclosingMethodDeclaration;
	}

	private TypeReference getTypeReference() {
		JDTIdentityMapper mapper = getJDTIdentifyMapperForCreation();
		TypeReference typeRef = mapper.getTypeRef(this.getCreation().resolveTypeBinding());
		return typeRef;
	}

	private MethodReference getEnclosingMethodReference() {
		JDTIdentityMapper mapper = getJDTIdentifyMapper(getEnclosingMethodDeclaration());
		MethodReference methodRef = mapper.getMethodRef(getEnclosingMethodDeclaration().resolveBinding());

		if (methodRef == null)
			throw new IllegalStateException(
					"Could not get method reference for: " + getEnclosingMethodDeclaration().getName());
		return methodRef;
	}

	private JDTIdentityMapper getJDTIdentifyMapperForCreation() {
		return getJDTIdentifyMapper(this.getCreation());
	}

	private static JDTIdentityMapper getJDTIdentifyMapper(ASTNode node) {
		return new JDTIdentityMapper(JavaSourceAnalysisScope.SOURCE, node.getAST());
	}

	public IType getEnclosingType() {
		return (IType) getEnclosingMethodDeclaration().resolveBinding().getDeclaringClass().getJavaElement();
	}

	public StreamExecutionMode getExecutionMode() {
		return executionMode;
	}

	private IR getEnclosingMethodIR() throws IOException, CoreException {
		IR ir = methodDeclarationToIRMap.get(getEnclosingMethodDeclaration());

		if (ir == null) {
			IClassHierarchy classHierarchy = getClassHierarchy();
			AnalysisOptions options = new AnalysisOptions();
			// options.getSSAOptions().setPiNodePolicy(SSAOptions.getAllBuiltInPiNodes());
			AnalysisCache cache = new AnalysisCache();

			// get the IR for the enclosing method.
			MethodReference methodRef = getEnclosingMethodReference();
			com.ibm.wala.classLoader.IMethod resolvedMethod = classHierarchy.resolveMethod(methodRef);
			ir = cache.getSSACache().findOrCreateIR(resolvedMethod, Everywhere.EVERYWHERE, options.getSSAOptions());

			if (ir == null)
				throw new IllegalStateException("IR is null for: " + resolvedMethod);

			methodDeclarationToIRMap.put(getEnclosingMethodDeclaration(), ir);
		}
		return ir;
	}

	public StreamOrdering getOrdering() {
		return ordering;
	}

	public RefactoringStatus getStatus() {
		return status;
	}

	private void inferExecution() {
		String methodIdentifier = getMethodIdentifier(this.getCreation().resolveMethodBinding());

		if (methodIdentifier.equals("parallelStream()"))
			this.setExecutionMode(StreamExecutionMode.PARALLEL);
		else
			this.setExecutionMode(StreamExecutionMode.SEQUENTIAL);
	}

	private void inferOrdering() throws IOException, CoreException, ClassHierarchyException, InvalidClassFileException,
			InconsistentPossibleStreamSourceOrderingException, NoniterablePossibleStreamSourceException,
			NoninstantiablePossibleStreamSourceException, CannotExtractSpliteratorException, PropertiesException,
			CancelException {
		ITypeBinding expressionTypeBinding = this.getCreation().getExpression().resolveTypeBinding();
		String expressionTypeQualifiedName = expressionTypeBinding.getErasure().getQualifiedName();
		IMethodBinding calledMethodBinding = this.getCreation().resolveMethodBinding();

		if (JdtFlags.isStatic(calledMethodBinding)) {
			// static methods returning unordered streams.
			if (expressionTypeQualifiedName.equals("java.util.stream.Stream")) {
				String methodIdentifier = getMethodIdentifier(calledMethodBinding);
				if (methodIdentifier.equals("generate(java.util.function.Supplier)"))
					this.setOrdering(StreamOrdering.UNORDERED);
			} else
				this.setOrdering(StreamOrdering.ORDERED);
		} else { // instance method.
			int valueNumber = getUseValueNumberForCreation();
			TypeInference inference = TypeInference.make(this.getEnclosingMethodIR(), false);
			Set<TypeAbstraction> possibleTypes = getPossibleTypes(valueNumber, inference);

			// Possible types: check each one.
			IMethod calledMethod = (IMethod) calledMethodBinding.getJavaElement();
			StreamOrdering ordering = inferStreamOrdering(possibleTypes, calledMethod);
			this.setOrdering(ordering);
		}

		EclipseProjectAnalysisEngine<InstanceKey> engine = this.getAnalysisEngine();

		// FIXME: Do we want a different entry point?
		DefaultEntrypoint entryPoint = new DefaultEntrypoint(this.getEnclosingMethodReference(),
				this.getClassHierarchy());
		Set<Entrypoint> entryPoints = Collections.singleton(entryPoint);
		AnalysisOptions analysisOptions = engine.getDefaultOptions(entryPoints);

		// FIXME: Do we need to build a new call graph for each entry point?
		// Doesn't make sense. Maybe we need to collect all enclosing methods
		// and use those as entry points.
		engine.buildSafeCallGraph(entryPoints);
		// TODO: Can I slice the graph so that only nodes relevant to the
		// instance in question are present?

		BenignOracle ora = new BenignOracle(engine.getCallGraph(), engine.getPointerAnalysis());
		PropertiesManager manager = PropertiesManager.initFromMap(Collections.emptyMap());
		TypeStateOptions typeStateOptions = new TypeStateOptions(manager);

		TypeReference typeReference = this.getTypeReference();
		IClass streamClass = engine.getClassHierarchy().lookupClass(typeReference);
		ITypeStateDFA dfa = new EventTrackingTypeStateProperty(engine.getClassHierarchy(),
				Collections.singleton(streamClass));

		ISafeSolver solver = TypestateSolverFactory.getSolver(analysisOptions, engine.getCallGraph(),
				engine.getPointerAnalysis(), engine.getHeapGraph(), dfa, ora, typeStateOptions, null, null, null, null);
		try {
			solver.perform(new NullProgressMonitor());
		} catch (SolverTimeoutException | MaxFindingsException | SetUpException | WalaException e) {
			logger.log(Level.SEVERE, "Exception caught during typestate analysis.", e);
			throw new RuntimeException(e);
		}
	}

	protected void setExecutionMode(StreamExecutionMode executionMode) {
		this.executionMode = executionMode;
	}

	protected void setOrdering(StreamOrdering ordering) {
		this.ordering = ordering;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Stream [streamCreation=");
		builder.append(creation);
		builder.append(", enclosingMethodDeclaration=");
		builder.append(enclosingMethodDeclaration);
		builder.append(", executionMode=");
		builder.append(executionMode);
		builder.append(", ordering=");
		builder.append(ordering);
		builder.append(", status=");
		builder.append(status);
		builder.append("]");
		return builder.toString();
	}
}
