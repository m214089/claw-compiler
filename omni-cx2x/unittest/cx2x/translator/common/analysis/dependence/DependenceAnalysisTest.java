/*
 * This file is released under terms of BSD license
 * See LICENSE file for more information
 */

package cx2x.translator.common.analysis.dependence;

import cx2x.translator.ClawTranslator;
import cx2x.configuration.Configuration;
import cx2x.configuration.CompilerDirective;
import cx2x.translator.directive.Directive;
import cx2x.translator.directive.generator.DirectiveGenerator;
import cx2x.translator.language.ClawPragma;
import cx2x.configuration.Target;
import cx2x.xcodeml.xnode.Xcode;
import cx2x.xcodeml.xnode.XcodeProgram;
import cx2x.xcodeml.xnode.Xnode;
import helper.TestConstant;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test the various features of the DependenceAnalysis class and the
 * IterationSpace class.
 *
 * @author clementval
 */
public class DependenceAnalysisTest {

  /**
   * Test the analysis feature of the DependenceAnalysis class.
   */
  @Test
  public void analyzeTest() {
    // Load test data file
    File f = new File(TestConstant.TEST_DEPENDENCE);
    assertTrue(f.exists());
    XcodeProgram xcodeml =
        XcodeProgram.createFromFile(TestConstant.TEST_DEPENDENCE);
    assertNotNull(xcodeml);

    // Match all the function definitions
    List<Xnode> functions = xcodeml.matchAll(Xcode.FFUNCTIONDEFINITION);
    assertEquals(2, functions.size());

    // Match all the pragma
    List<Xnode> pragmas = xcodeml.matchAll(Xcode.FPRAGMASTATEMENT);
    assertEquals(1, pragmas.size());

    // Get the function definition that interests us
    Xnode fctDef = functions.get(0);

    List<Xnode> loops = fctDef.matchAll(Xcode.FDOSTATEMENT);
    assertEquals(10, loops.size());

    // Create dependence analysis object for each do statement
    List<DependenceAnalysis> dependencies = new ArrayList<>();
    for(Xnode loop : loops) {
      try {
        dependencies.add(new DependenceAnalysis(loop));
      } catch(Exception e) {
        fail();
      }
    }

    // Assert the information for each do statement
    assertTrue(dependencies.get(0).isIndependent());
    assertTrue(dependencies.get(1).isIndependent());
    assertTrue(dependencies.get(2).isIndependent());

    assertFalse(dependencies.get(3).isIndependent());
    assertEquals(1, dependencies.get(3).getDistanceVector());
    assertEquals(DependenceDirection.BACKWARD,
        dependencies.get(3).getDirectionVector());

    assertFalse(dependencies.get(4).isIndependent());
    assertEquals(1, dependencies.get(4).getDistanceVector());
    assertEquals(DependenceDirection.FORWARD,
        dependencies.get(4).getDirectionVector());

    assertFalse(dependencies.get(5).isIndependent());
    assertEquals(1, dependencies.get(5).getDistanceVector());
    assertEquals(DependenceDirection.FORWARD,
        dependencies.get(5).getDirectionVector());

    assertFalse(dependencies.get(6).isIndependent());
    assertEquals(1, dependencies.get(6).getDistanceVector());
    assertEquals(DependenceDirection.BACKWARD,
        dependencies.get(6).getDirectionVector());

    assertTrue(dependencies.get(7).isIndependent());
    assertTrue(dependencies.get(8).isIndependent());
    assertTrue(dependencies.get(9).isIndependent());
  }

  /**
   * Test the IterationSpace feature of fusion and check the results.
   */
  @Test
  public void analyzeTest3d() {
    // Load test data file
    File f = new File(TestConstant.TEST_DEPENDENCE_3D);
    assertTrue(f.exists());
    XcodeProgram xcodeml =
        XcodeProgram.createFromFile(TestConstant.TEST_DEPENDENCE_3D);
    assertNotNull(xcodeml);

    // Match all the function definitions
    List<Xnode> functions = xcodeml.matchAll(Xcode.FFUNCTIONDEFINITION);
    assertEquals(2, functions.size());

    // Match all the pragmas
    List<Xnode> pragmas = xcodeml.matchAll(Xcode.FPRAGMASTATEMENT);
    assertEquals(1, pragmas.size());

    // Analyze the pragma
    Configuration.get().init(CompilerDirective.OPENACC, Target.GPU);
    Configuration.get().setMaxColumns(80);
    ClawTranslator translator = new ClawTranslator();
    DirectiveGenerator generator =
        Directive.createAcceleratorGenerator();
    ClawPragma main = null;
    try {
      main = ClawPragma.analyze(pragmas.get(0), generator, Target.GPU);
    } catch(Exception e) {
      fail();
    }

    // Get the function definition that interests us
    Xnode fctDef = functions.get(0);

    // Match all the do statements in the function
    List<Xnode> loops = fctDef.matchAll(Xcode.FDOSTATEMENT);
    assertEquals(11, loops.size());

    // Create an iteration space
    try {
      IterationSpace is = new IterationSpace(loops);
      is.tryFusion(xcodeml, translator, main);
      System.out.println();
      System.out.println("Iteration space before fusion");
      is.printDebug(true);

      loops = fctDef.matchAll(Xcode.FDOSTATEMENT);
      assertEquals(8, loops.size());

      is.reload(loops);
      System.out.println();
      System.out.println("Iteration space after fusion");
      is.printDebug(true);
    } catch(Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  @Test
  public void perfectlyNestedNoDependencyTest() {
    // Load test data file
    File f = new File(TestConstant.TEST_PERFECTLY_NESTED_NO_DEP);
    assertTrue(f.exists());
    XcodeProgram xcodeml =
        XcodeProgram.createFromFile(f.getPath());
    assertNotNull(xcodeml);

    // Match all the function definitions
    List<Xnode> functions = xcodeml.matchAll(Xcode.FFUNCTIONDEFINITION);
    assertEquals(1, functions.size());

    // Match all the pragmas
    List<Xnode> pragmas = xcodeml.matchAll(Xcode.FPRAGMASTATEMENT);
    assertEquals(1, pragmas.size());

    // Analyze the pragma
    Configuration.get().init(CompilerDirective.OPENACC, Target.GPU);
    Configuration.get().setMaxColumns(80);
    DirectiveGenerator generator =
        Directive.createAcceleratorGenerator();
    try {
      ClawPragma.analyze(pragmas.get(0), generator, Target.GPU);
    } catch(Exception e) {
      fail();
    }

    // Get the function definition that interests us
    Xnode fctDef = functions.get(0);

    // Match all the do statements in the function
    List<Xnode> loops = fctDef.matchAll(Xcode.FDOSTATEMENT);
    assertEquals(2, loops.size());

    // Create an iteration space
    try {
      IterationSpace is = new IterationSpace(loops);
      System.out.println();
      assertEquals(2, is.getNbLevel());
      is.printDebug(true);
      assertTrue(is.isPerfectlyNested());
    } catch(Exception e) {
      fail();
    }
  }
}
