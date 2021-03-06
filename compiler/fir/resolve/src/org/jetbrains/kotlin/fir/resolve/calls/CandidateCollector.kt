/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirQualifiedAccessExpressionImpl
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.transformQualifiedAccessUsingSmartcastInfo
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.firUnsafe
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.isExtensionFunctionType
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.util.OperatorNameConventions

open class CandidateCollector(
    val components: BodyResolveComponents,
    val resolutionStageRunner: ResolutionStageRunner
) {
    val groupNumbers = mutableListOf<Int>()
    val candidates = mutableListOf<Candidate>()

    var currentApplicability = CandidateApplicability.HIDDEN

    fun newDataSet() {
        groupNumbers.clear()
        candidates.clear()
        currentApplicability = CandidateApplicability.HIDDEN
    }

    open fun consumeCandidate(group: Int, candidate: Candidate): CandidateApplicability {
        val applicability = resolutionStageRunner.processCandidate(candidate)

        if (applicability > currentApplicability) {
            groupNumbers.clear()
            candidates.clear()
            currentApplicability = applicability
        }

        if (applicability == currentApplicability) {
            candidates.add(candidate)
            groupNumbers.add(group)
        }

        return applicability
    }

    fun bestCandidates(): List<Candidate> {
        if (groupNumbers.isEmpty()) return emptyList()
        if (groupNumbers.size == 1) return candidates
        val result = mutableListOf<Candidate>()
        var bestGroup = groupNumbers.first()
        for ((index, candidate) in candidates.withIndex()) {
            val group = groupNumbers[index]
            if (bestGroup > group) {
                bestGroup = group
                result.clear()
            }
            if (bestGroup == group) {
                result.add(candidate)
            }
        }
        return result
    }

    fun isSuccess(): Boolean {
        return currentApplicability >= CandidateApplicability.SYNTHETIC_RESOLVED
    }
}

// Collects properties that potentially could be invoke receivers, like 'propertyName()',
// and initiates further invoke resolution by adding property-bound invoke consumers
class InvokeReceiverCandidateCollector(
    val towerResolver: FirTowerResolver,
    val invokeCallInfo: CallInfo,
    components: BodyResolveComponents,
    val invokeConsumer: AccumulatingTowerDataConsumer,
    resolutionStageRunner: ResolutionStageRunner
) : CandidateCollector(components, resolutionStageRunner) {
    override fun consumeCandidate(group: Int, candidate: Candidate): CandidateApplicability {
        val applicability = super.consumeCandidate(group, candidate)

        if (applicability >= CandidateApplicability.SYNTHETIC_RESOLVED) {
            val session = components.session
            val symbol = candidate.symbol as FirCallableSymbol<*>
            val extensionReceiverExpression = candidate.extensionReceiverExpression()
            val useExtensionReceiverAsArgument =
                symbol.fir.receiverTypeRef == null &&
                        candidate.explicitReceiverKind == ExplicitReceiverKind.EXTENSION_RECEIVER &&
                        symbol.fir.returnTypeRef.isExtensionFunctionType()
            val explicitReceiver = FirQualifiedAccessExpressionImpl(null).apply {
                calleeReference = FirNamedReferenceWithCandidate(
                    null,
                    symbol.callableId.callableName,
                    candidate
                )
                dispatchReceiver = candidate.dispatchReceiverExpression()
                extensionReceiver = extensionReceiverExpression.takeIf { !useExtensionReceiverAsArgument } ?: FirNoReceiverExpression
                typeRef = towerResolver.typeCalculator.tryCalculateReturnType(candidate.symbol.firUnsafe())
            }.let {
                components.transformQualifiedAccessUsingSmartcastInfo(it)
            }
            val boundInvokeCallInfo = CallInfo(
                invokeCallInfo.callKind,
                explicitReceiver,
                invokeCallInfo.arguments,
                invokeCallInfo.isSafeCall,
                invokeCallInfo.typeArguments,
                session,
                invokeCallInfo.containingFile,
                invokeCallInfo.implicitReceiverStack,
                invokeCallInfo.expectedType,
                invokeCallInfo.outerCSBuilder,
                invokeCallInfo.lhs
            ).let {
                if (useExtensionReceiverAsArgument) it.withReceiverAsArgument(extensionReceiverExpression)
                else it
            }

            invokeConsumer.addConsumerAndProcessAccumulatedData(
                createSimpleFunctionConsumer(
                    session, OperatorNameConventions.INVOKE,
                    boundInvokeCallInfo, towerResolver.components, towerResolver.collector
                )
            )
        }

        return applicability
    }
}
