/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnnotatedDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByFqName
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

private val RETENTION_PARAMETER_NAME = Name.identifier("value")
private val TARGET_PARAMETER_NAME = Name.identifier("allowedTargets")

@OptIn(SymbolInternals::class)
fun FirAnnotationCall.getRetention(session: FirSession): AnnotationRetention {
    val annotationClassSymbol =
        (this.annotationTypeRef.coneType as? ConeClassLikeType)?.lookupTag?.toSymbol(session) as? FirRegularClassSymbol
            ?: return AnnotationRetention.RUNTIME
    annotationClassSymbol.ensureResolved(FirResolvePhase.BODY_RESOLVE)
    val annotationClass = annotationClassSymbol.fir
    return annotationClass.getRetention()
}

fun FirRegularClass.getRetention(): AnnotationRetention {
    val retentionAnnotation = getRetentionAnnotation() ?: return AnnotationRetention.RUNTIME
    val retentionArgument = retentionAnnotation.findArgumentByName(RETENTION_PARAMETER_NAME) as? FirQualifiedAccessExpression
        ?: return AnnotationRetention.RUNTIME
    val retentionName = (retentionArgument.calleeReference as? FirResolvedNamedReference)?.name?.asString()
        ?: return AnnotationRetention.RUNTIME
    return AnnotationRetention.values().firstOrNull { it.name == retentionName } ?: AnnotationRetention.RUNTIME
}

private val defaultAnnotationTargets = KotlinTarget.DEFAULT_TARGET_SET

@OptIn(SymbolInternals::class)
fun FirAnnotationCall.getAllowedAnnotationTargets(session: FirSession): Set<KotlinTarget> {
    if (annotationTypeRef is FirErrorTypeRef) return KotlinTarget.values().toSet()
    val annotationClassSymbol = (this.annotationTypeRef.coneType as? ConeClassLikeType)
        ?.fullyExpandedType(session)?.lookupTag?.toSymbol(session) ?: return defaultAnnotationTargets
    annotationClassSymbol.ensureResolved(FirResolvePhase.BODY_RESOLVE)
    val annotationClass = annotationClassSymbol.fir as? FirRegularClass
    return annotationClass?.getAllowedAnnotationTargets() ?: defaultAnnotationTargets
}

fun FirRegularClass.getAllowedAnnotationTargets(): Set<KotlinTarget> {
    val targetAnnotation = getTargetAnnotation() ?: return defaultAnnotationTargets
    if (targetAnnotation.argumentList.arguments.isEmpty()) return emptySet()
    val arguments = targetAnnotation.findArgumentByName(TARGET_PARAMETER_NAME)?.unfoldArrayOrVararg().orEmpty()

    return arguments.mapNotNullTo(mutableSetOf()) { argument ->
        val targetExpression = argument as? FirQualifiedAccessExpression
        val targetName = (targetExpression?.calleeReference as? FirResolvedNamedReference)?.name?.asString() ?: return@mapNotNullTo null
        KotlinTarget.values().firstOrNull { target -> target.name == targetName }
    }
}

fun FirAnnotatedDeclaration.getRetentionAnnotation(): FirAnnotationCall? {
    return getAnnotationByFqName(StandardNames.FqNames.retention)
}

fun FirAnnotatedDeclaration.getTargetAnnotation(): FirAnnotationCall? {
    return getAnnotationByFqName(StandardNames.FqNames.target)
}

fun FirAnnotationContainer.getAnnotationByClassId(classId: ClassId): FirAnnotationCall? {
    return annotations.find {
        (it.annotationTypeRef.coneType as? ConeClassLikeType)?.lookupTag?.classId == classId
    }
}

fun FirExpression.extractClassesFromArgument(): List<FirRegularClassSymbol> {
    return unfoldArrayOrVararg().mapNotNull {
        if (it !is FirGetClassCall) return@mapNotNull null
        val qualifier = it.argument as? FirResolvedQualifier ?: return@mapNotNull null
        qualifier.symbol as? FirRegularClassSymbol
    }
}

private fun FirExpression.unfoldArrayOrVararg(): List<FirExpression> {
    return when (this) {
        is FirVarargArgumentsExpression -> arguments
        is FirArrayOfCall -> arguments
        else -> return emptyList()
    }
}

