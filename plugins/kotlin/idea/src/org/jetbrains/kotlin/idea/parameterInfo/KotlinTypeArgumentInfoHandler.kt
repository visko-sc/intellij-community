// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.parameterInfo

import com.intellij.lang.parameterInfo.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.core.resolveCandidates
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.idea.util.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.kotlin.types.typeUtil.isAnyOrNullableAny
import org.jetbrains.kotlin.types.typeUtil.nullability

class KotlinClassConstructorInfoHandler : KotlinTypeArgumentInfoHandlerBase<ClassConstructorDescriptor>() {
    override fun fetchTypeParameters(descriptor: ClassConstructorDescriptor): List<TypeParameterDescriptor> = descriptor.typeParameters

    override fun findParameterOwners(argumentList: KtTypeArgumentList): Collection<ClassConstructorDescriptor>? {
        val userType = argumentList.parent as? KtUserType ?: return null
        val descriptors = userType.referenceExpression?.resolveMainReferenceToDescriptors()?.mapNotNull { it as? ClassConstructorDescriptor }
        return descriptors?.takeIf { it.isNotEmpty() }
    }

    override fun getArgumentListAllowedParentClasses() = setOf(KtUserType::class.java)
}

class KotlinClassTypeArgumentInfoHandler : KotlinTypeArgumentInfoHandlerBase<ClassDescriptor>() {
    override fun fetchTypeParameters(descriptor: ClassDescriptor) = descriptor.typeConstructor.parameters

    override fun findParameterOwners(argumentList: KtTypeArgumentList): Collection<ClassDescriptor>? {
        val userType = argumentList.parent as? KtUserType ?: return null
        val descriptors = userType.referenceExpression?.resolveMainReferenceToDescriptors()?.mapNotNull { it as? ClassDescriptor }
        return descriptors?.takeIf { it.isNotEmpty() }
    }

    override fun getArgumentListAllowedParentClasses() = setOf(KtUserType::class.java)
}

class KotlinFunctionTypeArgumentInfoHandler : KotlinTypeArgumentInfoHandlerBase<FunctionDescriptor>() {
    override fun fetchTypeParameters(descriptor: FunctionDescriptor) = descriptor.typeParameters

    override fun findParameterOwners(argumentList: KtTypeArgumentList): Collection<FunctionDescriptor>? {
        val callElement = argumentList.parent as? KtCallElement ?: return null
        val bindingContext = argumentList.safeAnalyzeNonSourceRootCode(BodyResolveMode.PARTIAL)
        val call = callElement.getCall(bindingContext) ?: return null
        val candidates = call.resolveCandidates(bindingContext, callElement.getResolutionFacade())
        return candidates
            .map { it.resultingDescriptor }
            .distinctBy { buildPresentation(it.typeParameters, -1).first }
    }

    override fun getArgumentListAllowedParentClasses() = setOf(KtCallElement::class.java)
}

abstract class KotlinTypeArgumentInfoHandlerBase<TParameterOwner : DeclarationDescriptor> :
    ParameterInfoHandlerWithTabActionSupport<KtTypeArgumentList, TParameterOwner, KtTypeProjection> {

    protected abstract fun fetchTypeParameters(descriptor: TParameterOwner): List<TypeParameterDescriptor>
    protected abstract fun findParameterOwners(argumentList: KtTypeArgumentList): Collection<TParameterOwner>?

    override fun getActualParameterDelimiterType() = KtTokens.COMMA
    override fun getActualParametersRBraceType() = KtTokens.GT

    override fun getArgumentListClass() = KtTypeArgumentList::class.java

    override fun getActualParameters(o: KtTypeArgumentList) = o.arguments.toTypedArray()

    override fun getArgListStopSearchClasses() =
        setOf(KtNamedFunction::class.java, KtVariableDeclaration::class.java, KtClassOrObject::class.java)

    override fun showParameterInfo(element: KtTypeArgumentList, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): KtTypeArgumentList? {
        val element = context.file.findElementAt(context.offset) ?: return null
        val argumentList = element.getParentOfType<KtTypeArgumentList>(true) ?: return null
        val argument = element.parents.takeWhile { it != argumentList }.lastOrNull() as? KtTypeProjection
        if (argument != null) {
            val arguments = getActualParameters(argumentList)
            val index = arguments.indexOf(element)
            context.setCurrentParameter(index)
            context.setHighlightedParameter(element)
        }
        return argumentList
    }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): KtTypeArgumentList? {
        val file = context.file as? KtFile ?: return null

        val token = file.findElementAt(context.offset) ?: return null
        val argumentList = token.getParentOfType<KtTypeArgumentList>(true) ?: return null

        val parameterOwners = findParameterOwners(argumentList) ?: return null

        context.itemsToShow = parameterOwners.toTypedArray<Any>()
        return argumentList
    }

    override fun updateParameterInfo(argumentList: KtTypeArgumentList, context: UpdateParameterInfoContext) {
        if (context.parameterOwner !== argumentList) {
            context.removeHint()
        }

        val offset = context.offset
        val parameterIndex = argumentList.allChildren
            .takeWhile { it.startOffset < offset }
            .count { it.node.elementType == KtTokens.COMMA }
        context.setCurrentParameter(parameterIndex)
    }

    override fun updateUI(itemToShow: TParameterOwner, context: ParameterInfoUIContext) {
        if (!updateUIOrFail(itemToShow, context)) {
            context.isUIComponentEnabled = false
            return
        }
    }

    private fun updateUIOrFail(itemToShow: TParameterOwner, context: ParameterInfoUIContext): Boolean {
        val currentIndex = context.currentParameterIndex
        if (currentIndex < 0) return false // by some strange reason we are invoked with currentParameterIndex == -1 during initialization

        val parameters = fetchTypeParameters(itemToShow)

        val (text, currentParameterStart, currentParameterEnd) = buildPresentation(parameters, currentIndex)

        context.setupUIComponentPresentation(
            text, currentParameterStart, currentParameterEnd,
            false/*isDisabled*/, false/*strikeout*/, false/*isDisabledBeforeHighlight*/,
            context.defaultParameterColor
        )

        return true
    }

    protected fun buildPresentation(
        parameters: List<TypeParameterDescriptor>,
        currentIndex: Int
    ): Triple<String, Int, Int> {
        var currentParameterStart = -1
        var currentParameterEnd = -1

        val text = buildString {
            var needWhere = false
            for ((index, parameter) in parameters.withIndex()) {
                if (index > 0) append(", ")

                if (index == currentIndex) {
                    currentParameterStart = length
                }

                if (parameter.isReified) {
                    append("reified ")
                }

                when (parameter.variance) {
                    Variance.INVARIANT -> {
                    }
                    Variance.IN_VARIANCE -> append("in ")
                    Variance.OUT_VARIANCE -> append("out ")
                }

                append(parameter.name.asString())

                val upperBounds = parameter.upperBounds
                if (upperBounds.size == 1) {
                    val upperBound = upperBounds.single()
                    if (!upperBound.isAnyOrNullableAny() || upperBound.nullability() == TypeNullability.NOT_NULL) { // skip Any? or Any!
                        append(" : ").append(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(upperBound))
                    }
                } else if (upperBounds.size > 1) {
                    needWhere = true
                }

                if (index == currentIndex) {
                    currentParameterEnd = length
                }
            }

            if (needWhere) {
                append(" where ")

                var needComma = false
                for (parameter in parameters) {
                    val upperBounds = parameter.upperBounds
                    if (upperBounds.size > 1) {
                        for (upperBound in upperBounds) {
                            if (needComma) append(", ")
                            needComma = true
                            append(parameter.name.asString())
                            append(" : ")
                            append(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(upperBound))
                        }
                    }
                }
            }
        }
        return Triple(text, currentParameterStart, currentParameterEnd)
    }
}