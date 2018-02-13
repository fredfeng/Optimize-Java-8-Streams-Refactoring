package edu.cuny.hunter.streamrefactoring.core.safe;

import java.util.Iterator;
import java.util.logging.Logger;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallString;
import com.ibm.wala.ipa.callgraph.propagation.cfa.CallStringContextSelector;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.strings.Atom;

import edu.cuny.hunter.streamrefactoring.core.utils.LoggerNames;
import edu.cuny.hunter.streamrefactoring.core.wala.nCFAContextWithReceiversSelector;

public class Util {

	/**
	 * The {@link TypeName} of the type {@link java.util.Arrays}.
	 */
	@SuppressWarnings("unused")
	private static final TypeName ARRAYS_TYPE_NAME = TypeName.string2TypeName("Ljava/util/Arrays");

	/**
	 * {@link Atom} corresponding the the stream() method.
	 */
	@SuppressWarnings("unused")
	private static final Atom STREAM_METHOD_NAME_ATOM = Atom.findOrCreateAsciiAtom("stream");

	/**
	 * The {@link TypeName} for the type {@link java.util.stream.StreamSupport}.
	 */
	@SuppressWarnings("unused")
	private static final TypeName STREAM_SUPPORT_TYPE_NAME = TypeName
			.string2TypeName("Ljava/util/stream/StreamSupport");

	private static final Logger LOGGER = Logger.getLogger(LoggerNames.LOGGER_NAME);

	/**
	 * True iff the given {@link InstanceKey} corresponds with the given
	 * {@link SSAInvokeInstruction} in the given {@link CallGraph}. In other words,
	 * the result is true iff the instruction is used to create the instance.
	 *
	 * @param instanceKey
	 *            An instance in question.
	 * @param instruction
	 *            An instruction in question. Should be corresponding to a ctor
	 *            call.
	 * @param callGraph
	 *            The corresponding call graph.
	 * @return True iff the given instruction was used to instantiate the given
	 *         instance key according to the given call graph.
	 * @throws NoApplicationCodeExistsInCallStringsException
	 *             Iff application code was not found while processing call strings.
	 */
	public static boolean instanceKeyCorrespondsWithInstantiationInstruction(InstanceKey instanceKey,
			SSAInvokeInstruction instruction, MethodReference instructionEnclosingMethod, CallGraph callGraph)
			throws NoApplicationCodeExistsInCallStringsException {
		// Creation sites for the instance with the given key in the given call
		// graph.
		Iterator<Pair<CGNode, NewSiteReference>> creationSites = instanceKey.getCreationSites(callGraph);

		CallSiteReference instructionCallSite = instruction.getCallSite();
		LOGGER.fine("instruction call site is: " + instructionCallSite);

		// did we see an application code entity in a call string? If not, this could
		// indicate that N is too small.
		boolean applicationCodeInCallString = false;

		// for each creation site.
		while (creationSites.hasNext()) {
			Pair<CGNode, NewSiteReference> pair = creationSites.next();

			// get the call string of the node in the call graph.
			ContextItem contextItem = pair.fst.getContext().get(CallStringContextSelector.CALL_STRING);
			CallString callString = (CallString) contextItem;

			// get the call site references corresponding to the call string.
			CallSiteReference[] callSiteRefs = callString.getCallSiteRefs();

			// get the methods corresponding to the call string.
			IMethod[] methods = callString.getMethods();

			// check sanity.
			assert callSiteRefs.length == methods.length : "Call sites and methods should correlate.";

			// for each call site reference.
			for (int i = 0; i < callSiteRefs.length; i++) {
				CallSiteReference callSiteReference = callSiteRefs[i];
				LOGGER.fine("Call site reference at " + i + " is: " + callSiteReference);

				IMethod method = methods[i];
				LOGGER.fine("Method at " + i + " is: " + method);

				// if we haven't encountered application code in the call string yet.
				if (!applicationCodeInCallString) {
					// get the class loaders.
					ClassLoaderReference callSiteReferenceClassLoader = callSiteReference.getDeclaredTarget()
							.getDeclaringClass().getClassLoader();
					IClassLoader methodClassLoader = method.getDeclaringClass().getClassLoader();

					// if either the call site reference class loader or the (enclosing) method
					// class loader is of type Application.
					if (callSiteReferenceClassLoader.equals(ClassLoaderReference.Application)
							|| methodClassLoader.equals(ClassLoaderReference.Application))
						// then, we've seen application code.
						applicationCodeInCallString = true;
				}

				// if the call site reference equals the call site corresponding
				// to the creation instruction.
				if (callSiteReference.equals(instructionCallSite)
						&& method.getReference().getSignature().equals(instructionEnclosingMethod.getSignature())) {
					LOGGER.fine("Match found. Instance key: " + instanceKey + " corresponds with instruction: "
							+ instruction + ".");
					return true;
				}
			}
		}
		LOGGER.fine("No match found. Instance key: " + instanceKey + " does not correspond with instruction: "
				+ instruction + ".");

		// if we did not encounter application code in the call strings.
		if (!applicationCodeInCallString)
			throw new NoApplicationCodeExistsInCallStringsException(
					"Could not find application code in call string while processing instance key: " + instanceKey
							+ " and instruction: " + instruction + ". This may indicate that the current value of N ("
							+ nCFAContextWithReceiversSelector.CONTEXT_LENGTH_FOR_STREAMS + ") is too small.");

		return false;
	}

	private Util() {
	}
}
