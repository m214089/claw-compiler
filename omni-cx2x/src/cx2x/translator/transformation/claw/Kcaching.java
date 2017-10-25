/*
 * This file is released under terms of BSD license
 * See LICENSE file for more information
 */

package cx2x.translator.transformation.claw;

import cx2x.translator.ClawTranslator;
import cx2x.translator.language.accelerator.AcceleratorHelper;
import cx2x.translator.language.base.ClawLanguage;
import cx2x.translator.transformation.ClawTransformation;
import cx2x.xcodeml.exception.IllegalTransformationException;
import cx2x.xcodeml.helper.XnodeUtil;
import cx2x.xcodeml.transformation.Transformation;
import cx2x.xcodeml.transformation.Translator;
import cx2x.xcodeml.xnode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A Kcaching transformation is an independent transformation. The
 * transformation consists of placing an assignment in a scalar variable and
 * use this variable in a loop body before updating it.
 *
 * @author clementval
 */
public class Kcaching extends ClawTransformation {

  private Xnode _doStmt;

  /**
   * Constructs a new Kcaching transformation triggered from a specific pragma.
   *
   * @param directive The directive that triggered the k caching transformation.
   */
  public Kcaching(ClawLanguage directive) {
    super(directive);
  }

  /**
   * @see Transformation#analyze(XcodeProgram, Translator)
   */
  @Override
  public boolean analyze(XcodeProgram xcodeml, Translator translator) {
    _doStmt = _claw.getPragma().matchAncestor(Xcode.FDOSTATEMENT);
    if(_doStmt == null) {
      xcodeml.addError("The kcache directive is not nested in a do statement",
          _claw.getPragma().lineNo());
      return false;
    }

    return true;
  }

  /**
   * @return Always false as independent transformation are applied one by one.
   * @see Transformation#canBeTransformedWith(XcodeProgram, Transformation)
   */
  @Override
  public boolean canBeTransformedWith(XcodeProgram xcodeml,
                                      Transformation other)
  {
    // independent transformation
    return false;
  }

  /**
   * @see Transformation#transform(XcodeProgram, Translator, Transformation)
   */
  @Override
  public void transform(XcodeProgram xcodeml, Translator translator,
                        Transformation other) throws Exception
  {
    // It might have change from the analysis
    _doStmt = _claw.getPragma().matchAncestor(Xcode.FDOSTATEMENT);

    // Check if there is an assignment

    // 1. Find the function/module declaration
    XfunctionDefinition fctDef = _claw.getPragma().findParentFunction();

    for(String data : _claw.getDataClauseValues()) {
      Xnode stmt = XnodeUtil.getFirstArrayAssign(_claw.getPragma(), data);

      boolean standardArrayRef = true;
      if(stmt != null) {
        for(Xnode el : stmt.matchDirectDescendant(Xcode.FARRAYREF).children()) {
          if(el.opcode() == Xcode.ARRAYINDEX) {

            if(!(el.matchDirectDescendant(Xcode.VAR) != null ||
                el.matchDirectDescendant(Xcode.FINTCONSTANT) != null))
            {
              standardArrayRef = false;
            }
          }
        }
      }

      if(stmt != null && standardArrayRef) {
        transformAssignStmt(xcodeml, fctDef, data, stmt, translator);
      } else {
        transformData(xcodeml, fctDef, data, translator);
      }

    }
    _claw.getPragma().delete();
  }

  /**
   * Apply the transformation for the data list.
   *
   * @param xcodeml    The XcodeML on which the transformations are applied.
   * @param fctDef     Function/module definition in which the data are nested.
   * @param data       Array identifier on which the caching is done.
   * @param translator Current instance of the translator.
   * @throws Exception If something prevent the transformation to be done.
   */
  private void transformData(XcodeProgram xcodeml, XfunctionDefinition fctDef,
                             String data,
                             Translator translator)
      throws Exception
  {

    List<Xnode> aRefs = checkOffsetAndGetArrayRefs(xcodeml, fctDef, data);

    // Generate the cache variable and its assignment
    String type = aRefs.get(0).getType();
    Xnode cacheVar = generateCacheVarAndAssignStmt(xcodeml, data, type, fctDef,
        aRefs.get(0), null);

    updateArrayRefWithCache(aRefs, cacheVar);

    AcceleratorHelper.generatePrivateClause(_claw, xcodeml, _claw.getPragma(),
        cacheVar.value());
  }

  /**
   * Apply the transformation for the LHS array reference.
   *
   * @param xcodeml    The XcodeML on which the transformations are applied.
   * @param fctDef     Function/module definition in which the data are nested.
   * @param data       Array identifier on which the caching is done.
   * @param stmt       First statement including the array ref on the lhs.
   * @param translator The translator used to applied the transformations.
   * @throws Exception If something prevent the transformation to be done.
   */
  private void transformAssignStmt(XcodeProgram xcodeml,
                                   XfunctionDefinition fctDef,
                                   String data,
                                   Xnode stmt,
                                   Translator translator) throws Exception
  {
    String type = stmt.matchDirectDescendant(Xcode.FARRAYREF).getType();
    List<Xnode> aRefs = checkOffsetAndGetArrayRefs(xcodeml, fctDef, data);

    Xnode cacheVar =
        generateCacheVarAndAssignStmt(xcodeml, data, type, fctDef, stmt, stmt);

    applyInitClause(xcodeml, translator, cacheVar, aRefs.get(0));

    updateArrayRefWithCache(aRefs, cacheVar);

    AcceleratorHelper.generatePrivateClause(_claw, xcodeml, _claw.getPragma(),
        cacheVar.value());
    stmt.delete();
  }

  /**
   * Apply the init clause if it was part of the kcache directive.
   *
   * @param xcodeml    Current program in which the transformation is
   *                   performed.
   * @param translator Current translator used to store elements information.
   * @param cacheVar   Newly created cache variable that will be used for the
   *                   initialization (rhs of the assign statement). Element
   *                   will be cloned before insertion.
   * @param arrayRef   Array reference to be modified that will be
   *                   used for the initialization (lhs of the assign
   *                   statement). Element will be cloned before insertion.
   */
  private void applyInitClause(XcodeProgram xcodeml, Translator translator,
                               Xnode cacheVar, Xnode arrayRef)
  {

    if(_claw.hasInitClause()) {
      ClawTranslator ct = (ClawTranslator) translator;
      Xnode initIfStmt = (Xnode) ct.hasElement(_doStmt);
      if(initIfStmt == null) {
        // If statement has not been created yet so we do it here
        initIfStmt = xcodeml.createIfThen();
        _claw.getPragma().copyEnhancedInfo(initIfStmt);
        Xnode logEq = xcodeml.createNode(Xcode.LOGEQEXPR);

        // Set lhs of equality
        logEq.append(_doStmt.matchDirectDescendant(Xcode.VAR), true);
        // Set rhs of equality
        logEq.append(_doStmt.matchDirectDescendant(Xcode.INDEXRANGE).
            matchDirectDescendant(Xcode.LOWERBOUND).child(0), true);

        initIfStmt.matchDirectDescendant(Xcode.CONDITION).append(logEq);
        _doStmt.body().insert(initIfStmt, false);
        ct.storeElement(_doStmt, initIfStmt);
      }

      Xnode initAssignment = xcodeml.createNode(Xcode.FASSIGNSTATEMENT);
      initAssignment.append(cacheVar, true); // set rhs
      initAssignment.append(arrayRef, true); // set lhs
      // Add assignment in the "then" body element
      initIfStmt.matchDirectDescendant(Xcode.THEN).body().
          append(initAssignment);
    }
  }

  /**
   * Generate a new variable name with the offsets information.
   *
   * @param basename The original variable name.
   * @param offsets  The offsets to be applied.
   * @return Original name with offsets information.
   */
  private String generateNameWithOffsetInfo(String basename,
                                            List<Integer> offsets)
  {
    StringBuilder newName = new StringBuilder(basename + "_k");
    for(Integer i : offsets) {
      if(i > 0) {
        newName.append("p").append(i);
      } else if(i < 0) {
        newName.append("m").append(Math.abs(i));
      } else {
        newName.append("_");
      }
    }
    return newName.toString();
  }

  /**
   * Generate correct offset inferred by the dimension of the variable.
   *
   * @param xcodeml The current program
   * @param fctDef  The function definition which holds the variable
   *                information.
   * @param var     The variable on which the offset are inferred.
   * @return List of integer representing the offset for the given variable.
   * @throws IllegalTransformationException if symbol id is not found.
   */
  private List<Integer> generateInferredOffsets(XcodeProgram xcodeml,
                                                XfunctionDefinition fctDef,
                                                String var)
      throws IllegalTransformationException
  {
    Xid id = fctDef.getSymbolTable().get(var);
    if(id == null) {
      throw new IllegalTransformationException("Variable " + var +
          " defined in the data clause has not been found",
          _claw.getPragma().lineNo()
      );
    }
    XbasicType basicType = xcodeml.getTypeTable().getBasicType(id);
    int dim = basicType.getDimensions();
    List<Integer> offsets = new ArrayList<>();
    for(int i = 0; i < dim; ++i) {
      offsets.add(0);
    }
    return offsets;
  }

  /**
   * Generate the necessary intermediate code to create the new cache variable
   * and set its assignment.
   *
   * @param xcodeml The current program.
   * @param var     The original variable name.
   * @param type    The original variable type.
   * @param fctDef  The function definition holding the variable.
   * @param rhs     The element that will be set as the rhs of the assignment.
   * @param stmt    The assign statement including the array ref.
   * @return The new created Xvar element.
   */
  private Xnode generateCacheVarAndAssignStmt(XcodeProgram xcodeml, String var,
                                              String type,
                                              XfunctionDefinition fctDef,
                                              Xnode rhs,
                                              Xnode stmt)
  {
    XbasicType t = xcodeml.getTypeTable().getBasicType(type); // TODO getType
    if(t.getIntent() != null || t.isAllocatable()) {
      // Type has an intent ... duplicate it and remove it
      XbasicType newType = t.cloneNode();
      type = xcodeml.getTypeTable().generateHash(XcodeType.REAL);
      newType.setType(type);
      newType.removeAttribute(Xattr.INTENT);
      newType.removeAttribute(Xattr.IS_ALLOCATABLE);

      XbasicType ref = xcodeml.getTypeTable().getBasicType(newType.getRef());
      if(ref != null && (ref.isAllocatable() || ref.hasIntent())) {
        // TODO is there several level to reach ref ? Check if ref is Freal ...
        XbasicType newRef = ref.cloneNode();
        // TODO generate appropriate type
        String refType = xcodeml.getTypeTable().generateHash(XcodeType.REAL);
        newRef.setType(refType);
        newRef.removeAttribute(Xattr.INTENT);
        newRef.removeAttribute(Xattr.IS_ALLOCATABLE);
        newType.setRef(refType);
        xcodeml.getTypeTable().add(newRef);
      }
      xcodeml.getTypeTable().add(newType);
    }


    String cacheName =
        generateNameWithOffsetInfo(var, _claw.getOffsets());

    // 2.2 inject a new entry in the symbol table
    if(!fctDef.getSymbolTable().contains(cacheName)) {
      Xid cacheVarId = xcodeml.createId(type, Xname.SCLASS_F_LOCAL, cacheName);
      fctDef.getSymbolTable().add(cacheVarId, false);
    }

    // 2.3 inject a new entry in the declaration table
    if(!fctDef.getDeclarationTable().contains(cacheName)) {
      Xnode cacheVarDecl = xcodeml.createVarDecl(type, cacheName);
      fctDef.getDeclarationTable().add(cacheVarDecl);
    }

    // 2.4 Prepare the new variable that is used for caching
    Xnode cacheVar = xcodeml.createVar(type, cacheName, Xscope.LOCAL);

    if(stmt == null) {
      Xnode cache1 = xcodeml.createNode(Xcode.FASSIGNSTATEMENT);
      cache1.append(cacheVar, false);
      cache1.append(rhs, true);
      _claw.getPragma().insertAfter(cache1);
    } else {
      /*
       * We replace an assignment of type
       * A = B
       * by
       * cache_A = B
       * A = cache_A
       */
      Xnode cache1 = xcodeml.createNode(Xcode.FASSIGNSTATEMENT);
      cache1.append(cacheVar);
      cache1.append(stmt.child(1), true);
      Xnode cache2 = xcodeml.createNode(Xcode.FASSIGNSTATEMENT);
      cache2.append(stmt.child(0), true);
      cache2.append(cacheVar, true);
      stmt.insertAfter(cache1);
      cache1.insertAfter(cache2);

    }
    return cacheVar;
  }

  private List<Xnode> checkOffsetAndGetArrayRefs(XcodeProgram xcodeml,
                                                 XfunctionDefinition fctDef,
                                                 String var)
      throws IllegalTransformationException
  {
    List<Integer> offsets = _claw.getOffsets();
    if(offsets.size() == 0) {
      offsets = generateInferredOffsets(xcodeml, fctDef, var);
    }

    List<Xnode> arrayRefs =
        XnodeUtil.getAllArrayReferencesByOffsets(_doStmt.body(),
            var, offsets);
    if(arrayRefs.size() == 0) {
      throw new IllegalTransformationException("Variable " + var +
          " defined in the data clause has not been found",
          _claw.getPragma().lineNo()
      );
    }
    return arrayRefs;
  }

  /**
   * Update the array references with the newly created cache variable.
   *
   * @param arrayRefs The list of array references to be updated.
   * @param cache     The new cache variable.
   */
  private void updateArrayRefWithCache(List<Xnode> arrayRefs, Xnode cache) {
    for(Xnode ref : arrayRefs) {
      // Swap arrayRef with the cache variable
      ref.insertAfter(cache.cloneNode());
      ref.delete();
    }
  }
}
