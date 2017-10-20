package edu.cuny.hunter.streamrefactoring.ui.tests;

import java.util.Set;

import edu.cuny.hunter.streamrefactoring.core.analysis.ExecutionMode;
import edu.cuny.hunter.streamrefactoring.core.analysis.Ordering;
import edu.cuny.hunter.streamrefactoring.core.analysis.PreconditionFailure;
import edu.cuny.hunter.streamrefactoring.core.analysis.PreconditionSuccess;
import edu.cuny.hunter.streamrefactoring.core.analysis.Refactoring;
import edu.cuny.hunter.streamrefactoring.core.analysis.TransformationAction;

class StreamAnalysisExpectedResult {
	private String expectedCreation;

	private Set<ExecutionMode> expectedExecutionModes;

	private Set<Ordering> expectedOrderings;

	private boolean expectingSideEffects;

	private boolean expectingStatefulIntermediateOperation;

	private boolean expectingThatReduceOrderingMatters;

	private Set<TransformationAction> expectedActions;

	private PreconditionSuccess expectedPassingPrecondition;

	private Refactoring expectedRefactoring;

	private int expectedStatusSeverity;

	private Set<PreconditionFailure> expectedFailures;

	public StreamAnalysisExpectedResult(String expectedCreation, Set<ExecutionMode> expectedExecutionModes,
			Set<Ordering> expectedOrderings, boolean expectingSideEffects,
			boolean expectingStatefulIntermediateOperation, boolean expectingThatReduceOrderingMatters,
			Set<TransformationAction> expectedActions, PreconditionSuccess expectedPassingPrecondition,
			Refactoring expectedRefactoring, int expectedStatusSeverity, Set<PreconditionFailure> expectedFailures) {
		this.expectedCreation = expectedCreation;
		this.expectedExecutionModes = expectedExecutionModes;
		this.expectedOrderings = expectedOrderings;
		this.expectingSideEffects = expectingSideEffects;
		this.expectingStatefulIntermediateOperation = expectingStatefulIntermediateOperation;
		this.expectingThatReduceOrderingMatters = expectingThatReduceOrderingMatters;
		this.expectedActions = expectedActions;
		this.expectedPassingPrecondition = expectedPassingPrecondition;
		this.expectedRefactoring = expectedRefactoring;
		this.expectedStatusSeverity = expectedStatusSeverity;
		this.expectedFailures = expectedFailures;
	}

	public String getExpectedCreation() {
		return expectedCreation;
	}

	public Set<ExecutionMode> getExpectedExecutionModes() {
		return expectedExecutionModes;
	}

	public Set<Ordering> getExpectedOrderings() {
		return expectedOrderings;
	}

	public boolean isExpectingSideEffects() {
		return expectingSideEffects;
	}

	public boolean isExpectingStatefulIntermediateOperation() {
		return expectingStatefulIntermediateOperation;
	}

	public boolean isExpectingThatReduceOrderingMatters() {
		return expectingThatReduceOrderingMatters;
	}

	public Set<TransformationAction> getExpectedActions() {
		return expectedActions;
	}

	public PreconditionSuccess getExpectedPassingPrecondition() {
		return expectedPassingPrecondition;
	}

	public Refactoring getExpectedRefactoring() {
		return expectedRefactoring;
	}

	public int getExpectedStatusSeverity() {
		return expectedStatusSeverity;
	}

	public Set<PreconditionFailure> getExpectedFailures() {
		return expectedFailures;
	}
}
