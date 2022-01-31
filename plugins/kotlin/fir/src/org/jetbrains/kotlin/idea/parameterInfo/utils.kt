// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.parameterInfo

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.calls.*
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KtFirDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithVisibility
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.psi.*

// Analogous to Call.resolveCandidates() in plugins/kotlin/core/src/org/jetbrains/kotlin/idea/core/Utils.kt
internal fun KtAnalysisSession.resolveCallCandidates(callElement: KtElement): List<CandidateWithMapping> {
    val (candidates, receiver, isSafeCall) = when (callElement) {
        is KtCallElement -> {
            val parent = callElement.parent
            val receiver = if (parent is KtQualifiedExpression && parent.selectorExpression == callElement) {
                parent.receiverExpression
            } else null
            Triple(callElement.resolveCandidates(), receiver, parent is KtSafeQualifiedExpression)
        }
        is KtArrayAccessExpression -> Triple(callElement.resolveCandidates(), callElement.arrayExpression, false)
        else -> return emptyList()
    }

    if (candidates.isEmpty()) return emptyList()
    val fileSymbol = callElement.containingKtFile.getFileSymbol()

    return candidates.filter {
        assert(it.calls.size == 1) { "resolveCandidates() should only have 1 candidate per KtCallInfo" }
        filterCandidate(it, callElement, fileSymbol, receiver, isSafeCall)
    }.mapNotNull {
        val functionCall = it.calls.first() as? KtFunctionCall<*> ?: return@mapNotNull null
        CandidateWithMapping(
            functionCall.partiallyAppliedSymbol.signature,
            functionCall.argumentMapping,
            isSuccessful = it is KtSuccessCallInfo
        )
    }
}

private fun KtAnalysisSession.filterCandidate(
    call: KtCallInfo,
    callElement: KtElement,
    fileSymbol: KtFileSymbol,
    receiver: KtExpression?,
    isSafeCall: Boolean
): Boolean {
    // Filter out hidden candidates. The diagnostic for callables with `@Deprecated(DeprecationLevel.HIDDEN)` is UNRESOLVED_REFERENCE.
    if (call is KtErrorCallInfo && call.diagnostic is KtFirDiagnostic.UnresolvedReference) {
        return false
    }

    val candidateCall = call.calls.first()
    if (candidateCall !is KtFunctionCall<*>) return false
    val candidateSymbol = candidateCall.partiallyAppliedSymbol.signature.symbol
    return filterCandidate(candidateSymbol, callElement, fileSymbol, receiver, isSafeCall)
}

internal fun KtAnalysisSession.filterCandidate(
    candidateSymbol: KtSymbol,
    callElement: KtElement,
    fileSymbol: KtFileSymbol,
    receiver: KtExpression?,
    isSafeCall: Boolean
): Boolean {
    if (callElement is KtConstructorDelegationCall) {
        // Exclude caller from candidates for `this(...)` delegated constructor calls.
        // The parent of KtDelegatedConstructorCall should be the KtConstructor. We don't need to get the symbol for the constructor
        // to determine if it's a self-call; we can just compare the candidate's PSI.
        val candidatePsi = candidateSymbol.psi
        if (candidatePsi != null && candidatePsi == callElement.parent) {
            return false
        }
    }

    if (receiver != null && candidateSymbol is KtCallableSymbol) {
        // Filter out candidates with wrong receiver
        val receiverType = receiver.getKtType()?.let { if (isSafeCall) it.withNullability(KtTypeNullability.NON_NULLABLE) else it }
            ?: error("Receiver should have a KtType")
        val candidateReceiverType = candidateSymbol.receiverType
        if (candidateReceiverType != null && receiverType.isNotSubTypeOf(candidateReceiverType)) return false
    }

    // Filter out candidates not visible from call site
    if (candidateSymbol is KtSymbolWithVisibility && !isVisible(candidateSymbol, fileSymbol, receiver, callElement)) return false

    return true
}

internal data class CandidateWithMapping(
    val candidate: KtFunctionLikeSignature<KtFunctionLikeSymbol>,
    val argumentMapping: LinkedHashMap<KtExpression, KtVariableLikeSignature<KtValueParameterSymbol>>,
    val isSuccessful: Boolean,
)