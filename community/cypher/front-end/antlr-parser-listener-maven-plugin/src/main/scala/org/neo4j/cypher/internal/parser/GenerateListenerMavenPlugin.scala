/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.parser

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier.isAbstract
import java.lang.reflect.Modifier.isFinal
import java.lang.reflect.Modifier.isPublic
import java.lang.reflect.Modifier.isStatic

import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.Try

import javax.lang.model.element.Modifier

/**
 * Generates a custom optimised parser listener for the antlr CypherParser.
 */
@Mojo(
  name = "generate-antlr-listener",
  defaultPhase = LifecyclePhase.GENERATE_SOURCES,
  requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
  requiresProject = true,
  threadSafe = true
)
class GenerateListenerMavenPlugin extends AbstractMojo {

  @Parameter(defaultValue = "${project}")
  protected var project: MavenProject = _

  @Parameter(defaultValue = "${project.build.directory}/generated-sources/parser")
  protected var outputDirectory: File = _

  override def execute(): Unit = {
    val meta = parserMeta()
    // CypherParserListener.java
    val parserListenerSpec = TypeSpec.interfaceBuilder("CypherParserListener")
      .addModifiers(Modifier.PUBLIC)
      .addSuperinterface(classOf[ParseTreeListener])
      .addMethods(genExitMethods(meta).asJava)
      .addJavadoc(s"A ParseTreeListener for CypherParser.\nGenerated by ${getClass.getCanonicalName}")
      .build
    JavaFile.builder(getClass.getPackageName, parserListenerSpec).build
      .writeTo(outputDirectory)

    // AbstractAstBuilder.java
    val abstractAstBuilderSpec = TypeSpec.classBuilder("AbstractAstBuilder")
      .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
      .addSuperinterface(ClassName.get(getClass.getPackageName, "CypherParserListener"))
      .addMethods(genExitAllMethod(meta).asJava)
      .addJavadoc(s"Optimised implementation of CypherParserListener.\nGenerated by ${getClass.getCanonicalName}")
      .build
    JavaFile.builder(getClass.getPackageName, abstractAstBuilderSpec).build
      .writeTo(outputDirectory)

    project.addCompileSourceRoot(outputDirectory.getPath)
  }

  /*
   * Generate exit methods for all rule context classes,
   * for example: void exitStatement(CypherParser.StatementContext ctx);
   */
  private def genExitMethods(meta: ParserMeta) = {
    meta.allCtxClasses.map { cls =>
      MethodSpec.methodBuilder(exitName(cls))
        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
        .returns(classOf[Unit])
        .addParameter(cls, "ctx")
        .build()
    }
  }

  /*
   * Generate the optimised exitEveryRule method.
   */
  private def genExitAllMethod(meta: ParserMeta) = {
    // Override the exit method for rule contexts that have multiple subclasses (that share the same rule index).
    val groupExits = meta.indexGroups
      .filter(_.rules.size > 1)
      .map { group =>
        def genIf(rule: Class[_]): String =
          s"if (ctx instanceof ${name(rule)} subCtx) ${exitName(rule)}(subCtx);"

        MethodSpec.methodBuilder(exitName(group.targetCls))
          .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
          .addAnnotation(classOf[Override])
          .returns(classOf[Unit])
          .addParameter(group.targetCls, "ctx")
          .addCode(
            s"""
               |${genIf(group.rules.head)}
               |${group.rules.tail.map(r => s"else ${genIf(r)}").mkString("\n")}
               |else throw new IllegalStateException("Unknown class " + ctx.getClass());
               |""".stripMargin.trim
          )
          .build()
      }

    // Generate exitEveryRule
    val exitAllMethod = MethodSpec.methodBuilder("exitEveryRule")
      .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
      .addAnnotation(classOf[Override])
      .returns(classOf[Unit])
      .addParameter(classOf[ParserRuleContext], "ctx")
      .addJavadoc(
        """
          |Optimised exit method.
          |
          |This compiles into a blazingly fast tableswitch (jump table)
          |and has been shown to be faster than the generated listeners that antlr provides.
          |""".stripMargin
      )
      .addCode(
        s"""
           |switch (ctx.getRuleIndex()) {
           |${meta.indexGroups.map(g => genCase(g.index, g.targetCls)).mkString("\n")}
           |  default -> throw new IllegalStateException("Unknown rule index " + ctx.getRuleIndex());
           |}
           |""".stripMargin.trim
      )
      .build()

    Seq(exitAllMethod) ++ groupExits
  }

  private def exitName(cls: Class[_]): String = "exit" + cls.getSimpleName.replace("Context", "")

  private def genCase(index: IndexMeta, cls: Class[_]) = {
    val nullable = nullableFields(cls)
    if (nullable.nonEmpty) {
      s"""  case ${name(index.field)} -> {
         |    final var subCtx = (${name(cls)}) ctx;
         |    ${exitName(cls)}(subCtx);
         |    ${nullable.map(f => s"subCtx.${f.getName} = null;").mkString(" ")}
         |  }""".stripMargin
    } else {
      s"  case ${name(index.field)} -> ${exitName(cls)}((${name(cls)}) ctx);"
    }
  }

  private def parserMeta(): ParserMeta = {
    // Find the fields that contains the rule index, for example `CypherParser.RULE_statements`
    val indexFields = classOf[CypherParser].getFields.toSeq
      .filter(isRuleIndexField)
      .groupBy(f => f.getInt(null))
      .map {
        case (index, Seq(field)) => index -> field
        case other               => throw new IllegalStateException("Assumptions broken " + other)
      }

    // Find all antlr parser rule classes in the CypherParser (subclasses of AstRuleCtx)
    val allCtxClasses = classOf[CypherParser].getDeclaredClasses.toSeq
      .filter(classOf[AstRuleCtx].isAssignableFrom)
      .filter(c => isStatic(c.getModifiers))

    // Map antlr rule class to it's rule index
    val indexGroups = allCtxClasses
      .map { ctxClass =>
        val ruleIndex = newCtxInstance(ctxClass).getRuleIndex
        ruleIndex -> ctxClass
      }
      // Rule index is sometimes shared between multiple context objects
      .groupMap { case (index, _) => index } { case (_, cls) => cls }
      .toSeq
      .map {
        case (index, Seq(ctxClass)) =>
          IndexGroup(IndexMeta(index, indexFields(index)), ctxClass, Seq(ctxClass))
        case (index, ctxClasses) =>
          ctxClasses.filter(c => ctxClasses.forall(c.isAssignableFrom)) match {
            case Seq(parent) =>
              IndexGroup(IndexMeta(index, indexFields(index)), parent, ctxClasses.filter(_ != parent))
            case _ => throw new IllegalStateException(s"Assumptions broke: $ctxClasses")
          }
      }
      .sortBy(_.index.index)

    ParserMeta(allCtxClasses, indexGroups)
  }

  /*
   * Get an instance of a AstRuleCtx subclass.
   *
   * This is a hack, but provides some additional safety.
   * It enables us to check that our assumptions about the mappings
   * between antlr rule contexts and rule index are correct.
   */
  private def newCtxInstance(cls: Class[_]): AstRuleCtx = {
    val superCls = cls.getSuperclass.asInstanceOf[Class[_]]
    val parent = if (classOf[AstRuleCtx].isAssignableFrom(superCls) && !isAbstract(superCls.getModifiers)) {
      // Some rule contexts need a parent of specific type in their constructor
      newCtxInstance(superCls)
    } else {
      new ParserRuleContext()
    }
    cls.getDeclaredConstructors
      .flatMap { c =>
        // We assume one of these constructors will work
        c.getParameterCount match {
          case 0 => Try(c.newInstance().asInstanceOf[AstRuleCtx]).toOption
          case 1 => Try(c.newInstance(parent).asInstanceOf[AstRuleCtx]).toOption
          case 2 => Try(c.newInstance(parent, -1).asInstanceOf[AstRuleCtx]).toOption
          case _ => None
        }
      }
      .headOption
      .getOrElse(throw new IllegalStateException(s"Failed to instantiate $cls"))
  }

  private def isRuleIndexField(f: Field) = {
    val modifiers = f.getModifiers
    f.getName.startsWith("RULE_") &&
    f.getType == classOf[Int] &&
    isPublic(modifiers) &&
    isStatic(modifiers) &&
    isFinal(modifiers)
  }

  private def nullableFields(cls: Class[_]): Seq[Field] = {
    if (classOf[AstRuleCtx].isAssignableFrom(cls)) {
      cls.getDeclaredFields.filter { f =>
        val modifiers = f.getModifiers
        (classOf[AstRuleCtx].isAssignableFrom(f.getType) || f.getType == classOf[java.util.List[_]]) &&
        isPublic(modifiers) &&
        !isStatic(modifiers) &&
        !isFinal(modifiers)
      }
    } else {
      Seq.empty
    }
  }

  private def name(cls: Class[_]): String = cls.getCanonicalName.replace(cls.getPackageName + ".", "")
  private def name(field: Field): String = name(field.getDeclaringClass) + "." + field.getName
}

/**
 * @param allCtxClasses all existing rule context classes (subclasses of [[AstRuleCtx]]) in the antlr parser
 * @param indexGroups all rule index groups (rule index is sometimes shared)
 */
case class ParserMeta(allCtxClasses: Seq[Class[_]], indexGroups: Seq[IndexGroup])

/**
 * @param index antlr parser rule index (for example CypherParser.RULE_statements)
 * @param targetCls rule context class that use the rule index
 * @param rules other rule context classes that share this rule index (because they're subclasses of targetCls)
 */
case class IndexGroup(index: IndexMeta, targetCls: Class[_], rules: Seq[Class[_]])

/**
 * @param index antlr parser rule index (for example CypherParser.RULE_statements)
 * @param field the field where this rule index is stored (like CypherParser.RULE_statements)
 */
case class IndexMeta(index: Int, field: Field)
