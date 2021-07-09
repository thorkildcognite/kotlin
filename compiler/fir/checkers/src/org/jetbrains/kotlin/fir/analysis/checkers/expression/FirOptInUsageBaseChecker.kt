/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.analysis.checkers.*
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByFqName
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toFirRegularClass
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.processDirectlyOverriddenFunctions
import org.jetbrains.kotlin.fir.scopes.processDirectlyOverriddenProperties
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.checkers.OptInNames
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.addIfNotNull

object FirOptInUsageBaseChecker {
    internal data class Experimentality(val annotationClassId: ClassId, val severity: Severity, val message: String?) {
        enum class Severity { WARNING, ERROR }
        companion object {
            val DEFAULT_SEVERITY = Severity.ERROR
        }
    }

    fun FirRegularClassSymbol.loadExperimentalityForMarkerAnnotation(): Experimentality? {
        ensureResolved(FirResolvePhase.BODY_RESOLVE)
        @OptIn(SymbolInternals::class)
        return fir.loadExperimentalityForMarkerAnnotation()
    }

    private fun loadExperimentalitiesFromTypeArguments(
        context: CheckerContext,
        typeArguments: List<FirTypeProjection>
    ): Set<Experimentality> {
        if (typeArguments.isEmpty()) return emptySet()
        return loadExperimentalitiesFromConeArguments(context, typeArguments.map { it.toConeTypeProjection() })
    }

    internal fun loadExperimentalitiesFromConeArguments(
        context: CheckerContext,
        typeArguments: List<ConeTypeProjection>
    ): Set<Experimentality> {
        if (typeArguments.isEmpty()) return emptySet()
        val result = SmartSet.create<Experimentality>()
        typeArguments.forEach {
            if (!it.isStarProjection) it.type?.addExperimentalities(context, result)
        }
        return result
    }

    internal fun FirAnnotatedDeclaration.loadExperimentalities(
        context: CheckerContext,
        knownExperimentalities: SmartSet<Experimentality>? = null,
        visited: MutableSet<FirAnnotatedDeclaration> = mutableSetOf(),
        fromSetter: Boolean = false,
    ): Set<Experimentality> {
        if (!visited.add(this)) return emptySet()
        val result = knownExperimentalities ?: SmartSet.create()
        val session = context.session
        if (this is FirCallableMemberDeclaration) {
            val parentClass = containingClass()?.toFirRegularClass(session)
            if (this.isSubstitutionOrIntersectionOverride) {
                val parentClassScope = parentClass?.unsubstitutedScope(context)
                if (this is FirSimpleFunction) {
                    parentClassScope?.processDirectlyOverriddenFunctions(symbol) {
                        it.fir.loadExperimentalities(context, result, visited)
                        ProcessorAction.NEXT
                    }
                } else if (this is FirProperty) {
                    parentClassScope?.processDirectlyOverriddenProperties(symbol) {
                        it.fir.loadExperimentalities(context, result, visited, fromSetter)
                        ProcessorAction.NEXT
                    }
                }
            }
            if (this !is FirConstructor) {
                // Note: coneType here crashes on overridden members
                returnTypeRef.coneTypeSafe<ConeKotlinType>().addExperimentalities(context, result, visited)
                receiverTypeRef?.coneTypeSafe<ConeKotlinType>().addExperimentalities(context, result, visited)
                if (this is FirSimpleFunction) {
                    valueParameters.forEach {
                        it.returnTypeRef.coneTypeSafe<ConeKotlinType>().addExperimentalities(context, result, visited)
                    }
                }
            }
            parentClass?.loadExperimentalities(context, result, visited)
            if (fromSetter && this is FirProperty) {
                setter?.loadExperimentalities(context, result, visited)
            }
        } else if (this is FirRegularClass && !this.isLocal) {
            outerClass(context)?.loadExperimentalities(context, result, visited)
        }

        for (annotation in annotations) {
            val annotationType = annotation.annotationTypeRef.coneTypeSafe<ConeClassLikeType>()
            if (annotation.useSiteTarget != AnnotationUseSiteTarget.PROPERTY_SETTER || fromSetter) {
                result.addIfNotNull(
                    annotationType?.lookupTag?.toFirRegularClass(
                        session
                    )?.loadExperimentalityForMarkerAnnotation()
                )
            }
        }

        if (this is FirTypeAlias) {
            // coneTypeSafe: see FirIdeSpecTest compiler/tests-spec/testData/diagnostics/notLinked/dfa/neg/6.kt
            expandedTypeRef.coneTypeSafe<ConeKotlinType>().addExperimentalities(context, result, visited)
        }

        if (getAnnotationByClassId(OptInNames.WAS_EXPERIMENTAL_CLASS_ID) != null) {
            val accessibility = checkSinceKotlinVersionAccessibility(context)
            if (accessibility is FirSinceKotlinAccessibility.NotAccessibleButWasExperimental) {
                accessibility.markerClasses.forEach {
                    result.addIfNotNull(it.fir.loadExperimentalityForMarkerAnnotation())
                }
            }
        }

        // TODO: getAnnotationsOnContainingModule
        return result
    }

    private fun ConeKotlinType?.addExperimentalities(
        context: CheckerContext,
        result: SmartSet<Experimentality>,
        visited: MutableSet<FirAnnotatedDeclaration> = mutableSetOf()
    ) {
        when (this) {
            !is ConeClassLikeType -> return
            else -> {
                val expandedType = fullyExpandedType(context.session)
                if (this === expandedType) {
                    expandedType.lookupTag.toFirRegularClass(context.session)?.loadExperimentalities(
                        context, result, visited
                    )
                    typeArguments.forEach {
                        if (!it.isStarProjection) it.type?.addExperimentalities(context, result, visited)
                    }
                } else {
                    lookupTag.toSymbol(context.session)?.fir?.loadExperimentalities(
                        context, result, visited
                    )
                    expandedType.typeArguments.forEach {
                        if (!it.isStarProjection) it.type?.addExperimentalities(context, result, visited)
                    }
                }
            }
        }
    }

    internal fun FirRegularClass.loadExperimentalityForMarkerAnnotation(): Experimentality? {
        val experimental = getAnnotationByClassId(OptInNames.REQUIRES_OPT_IN_CLASS_ID)
            ?: return null

        val levelArgument = experimental.findArgumentByName(LEVEL) as? FirQualifiedAccessExpression
        val levelName = (levelArgument?.calleeReference as? FirResolvedNamedReference)?.name?.asString()
        val level = OptInLevel.values().firstOrNull { it.name == levelName } ?: OptInLevel.DEFAULT
        val message = (experimental.findArgumentByName(MESSAGE) as? FirConstExpression<*>)?.value as? String
        return Experimentality(symbol.classId, level.severity, message)
    }

    internal fun reportNotAcceptedExperimentalities(
        experimentalities: Collection<Experimentality>,
        element: FirElement,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        for ((annotationClassId, severity, message) in experimentalities) {
            if (!isExperimentalityAcceptableInContext(annotationClassId, context)) {
                val diagnostic = when (severity) {
                    Experimentality.Severity.WARNING -> FirErrors.EXPERIMENTAL_API_USAGE
                    Experimentality.Severity.ERROR -> FirErrors.EXPERIMENTAL_API_USAGE_ERROR
                }
                val reportedMessage = message ?: when (severity) {
                    Experimentality.Severity.WARNING -> "This declaration is experimental and its usage should be marked"
                    Experimentality.Severity.ERROR -> "This declaration is experimental and its usage must be marked"
                }
                reporter.reportOn(element.source, diagnostic, annotationClassId.asSingleFqName(), reportedMessage, context)
            }
        }
    }

    private fun isExperimentalityAcceptableInContext(
        annotationClassId: ClassId,
        context: CheckerContext
    ): Boolean {
        val languageVersionSettings = context.session.languageVersionSettings
        val fqNameAsString = annotationClassId.asFqNameString()
        if (fqNameAsString in languageVersionSettings.getFlag(AnalysisFlags.experimental) ||
            fqNameAsString in languageVersionSettings.getFlag(AnalysisFlags.useExperimental)
        ) {
            return true
        }
        for (annotationContainer in context.annotationContainers) {
            if (annotationContainer.isExperimentalityAcceptable(annotationClassId)) {
                return true
            }
        }
        return false
    }

    private fun FirAnnotationContainer.isExperimentalityAcceptable(annotationClassId: ClassId): Boolean {
        return getAnnotationByClassId(annotationClassId) != null || isAnnotatedWithUseExperimentalOf(annotationClassId)
    }

    private fun FirAnnotationContainer.isAnnotatedWithUseExperimentalOf(annotationClassId: ClassId): Boolean {
        for (annotation in annotations) {
            val coneType = annotation.annotationTypeRef.coneType as? ConeClassLikeType
            if (coneType?.lookupTag?.classId != OptInNames.OPT_IN_CLASS_ID) {
                continue
            }
            val annotationClasses = annotation.findArgumentByName(OptInNames.USE_EXPERIMENTAL_ANNOTATION_CLASS) ?: continue
            if (annotationClasses.extractClassesFromArgument().any {
                    it.classId == annotationClassId
                }
            ) {
                return true
            }
        }
        return false
    }

    private val LEVEL = Name.identifier("level")
    private val MESSAGE = Name.identifier("message")

    private enum class OptInLevel(val severity: Experimentality.Severity) {
        WARNING(Experimentality.Severity.WARNING),
        ERROR(Experimentality.Severity.ERROR),
        DEFAULT(Experimentality.DEFAULT_SEVERITY)
    }
}
