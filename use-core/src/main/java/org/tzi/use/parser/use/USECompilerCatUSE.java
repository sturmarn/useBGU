package org.tzi.use.parser.use;

import org.antlr.runtime.ANTLRInputStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.Token;
import org.tzi.use.parser.MLMContext;
import org.tzi.use.parser.ParseErrorHandler;
import org.tzi.use.parser.catmlm.CatUSELexer;
import org.tzi.use.parser.catmlm.CatUSEParser;
import org.tzi.use.parser.ocl.ASTSimpleType;
import org.tzi.use.parser.ocl.ASTType;
import org.tzi.use.uml.mm.MMultiLevelModel;
import org.tzi.use.uml.mm.MultiLevelModelFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler for CatMLM specifications.
 *
 * <p>Mirrors {@link USECompilerMLM} exactly, except that between parsing
 * and code generation it runs {@link #desugar}, which translates the
 * CatMLM-shaped parse tree ({@link ASTCatUSEModel}) into a plain
 * {@link ASTMultiLevelModel} -- the very same AST type
 * {@code USECompilerMLM} builds from ordinary MLMUse source. Everything
 * downstream of that point ({@code .gen(ctx)}, {@code MMultiLevelModel},
 * well-definedness checking, the GUI, ...) is completely unmodified and
 * has no notion that CatMLM exists.
 *
 * <p>Translation rules implemented here (see the CatMLM-to-MLMUse
 * design writeup for the full rationale):
 * <ol>
 *   <li>{@code category ID} / {@code catAssociation ID} desugar to a
 *       plain {@code class}/{@code association} -- the tag is dropped
 *       from the classifier itself and acted on below instead.</li>
 *   <li>{@code model ID1 < ID2} becomes a {@code mediator ID1 < ID2}
 *       (or {@code < NONE} for a level with no parent).</li>
 *   <li>{@code clabject C : A, B} becomes one plain
 *       {@code clabject C : A} / {@code clabject C : B} per listed
 *       powerclass.</li>
 *   <li>Every attribute/constraint tagged {@code catAtt}/{@code catConstr}
 *       on a powerclass (walking that powerclass's own same-level
 *       superclass chain, since a clabject can instantiate a subclass of
 *       the class that actually declares the tagged feature) is
 *       cancelled automatically in every clabject instantiating that
 *       powerclass -- the corrected reading of the tag confirmed against
 *       the worked ABCD example: tagged features are the ones cancelled
 *       everywhere, not the ones preserved.</li>
 *   <li>Every {@code catAssociation}-tagged association with an end
 *       targeting a powerclass being instantiated has its *other* end's
 *       role name(s) cancelled in the resulting clabject. This is what
 *       stops a clabject that instantiates two powerclasses (e.g. one
 *       via {@code aa1}, one via {@code bb1}) from ending up with two
 *       different roles that happen to share a name -- confirmed to be
 *       necessary, not optional, by an actual "Role r1 is already
 *       defined" failure hit while testing this against the ABCD
 *       example before this rule was implemented.</li>
 *   <li>An {@code inter-associations}/{@code inter-constraints} section
 *       (declared outside every {@code catLevel}, never inside one) passes
 *       straight through onto the resulting {@code ASTMultiModel} --
 *       these already parse via the identical reused
 *       {@code interAssociationDefinition}/{@code interInvariant}/
 *       {@code interPrePost} rules plain MLM-USE uses, so no translation
 *       happens here at all, only forwarding. There is deliberately no
 *       {@code inter-classes} support, and no {@code catAssociation}-style
 *       cancellation for inter-associations -- see {@code known-issues.org}
 *       Issue 9 for the consequence this has for association classes.</li>
 * </ol>
 *
 * @author Claude
 */
public class USECompilerCatUSE {

    private USECompilerCatUSE() {}

    public static MMultiLevelModel compileCatUSESpecification(InputStream in,
                                                                String inName,
                                                                PrintWriter err,
                                                                MultiLevelModelFactory factory) {
        MMultiLevelModel result = null;
        ParseErrorHandler errHandler = new ParseErrorHandler(inName, err);

        ANTLRInputStream aInput;
        try {
            aInput = new ANTLRInputStream(in);
            aInput.name = inName;
        } catch (IOException e1) {
            err.println(e1.getMessage());
            return null;
        }

        CatUSELexer lexer = new CatUSELexer(aInput);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        CatUSEParser parser = new CatUSEParser(tokenStream);

        lexer.init(errHandler);
        parser.init(errHandler);

        try {
            ASTCatUSEModel catAst = parser.catUSE_model();
            if (errHandler.errorCount() == 0) {
                ASTMultiLevelModel mlmAst = desugar(catAst);
                MLMContext ctx = new MLMContext(inName, err, null, factory);
                result = mlmAst.gen(ctx);
                if (ctx.errorCount() > 0) {
                    result = null;
                }
            }
        } catch (RecognitionException e) {
            err.println(parser.getSourceName() + ":" +
                    e.line + ":" +
                    e.charPositionInLine + ": " +
                    e.getMessage());
        } catch (Exception e) {
            err.println(inName + ": " + e.getMessage());
        }

        err.flush();
        return result;
    }

    /**
     * Translation rules 1-4: CatMLM parse tree -&gt; plain MLMUse AST.
     */
    static ASTMultiLevelModel desugar(ASTCatUSEModel cat) throws Exception {
        ASTMultiLevelModel mlm = new ASTMultiLevelModel(cat.name());
        ASTMultiModel multiModel = new ASTMultiModel();

        // Index every level's declared classifiers by name, per level,
        // so powerclasses and same-level superclass chains can be
        // resolved while walking clabjects below.
        Map<String, Map<String, ASTClass>> classesByLevel = new HashMap<>();
        Map<String, ASTCatLevel> levelsByName = new HashMap<>();
        for (ASTCatLevel level : cat.levels()) {
            levelsByName.put(level.name().getText(), level);
            Map<String, ASTClass> byName = new HashMap<>();
            for (ASTClass c : level.classifiers()) {
                byName.put(c.getName().getText(), c);
            }
            classesByLevel.put(level.name().getText(), byName);
        }

        for (ASTCatLevel level : cat.levels()) {
            boolean hasParent = level.parentName() != null && !level.parentName().getText().equals("NONE");
            Map<String, ASTClass> ownClasses = classesByLevel.get(level.name().getText());

            // A clabject only means something relative to a parent level's
            // powerclasses. A level with no parent (top-level, or a bare
            // "model X" with no "< Y" at all) that still declares clabjects
            // is almost certainly a missing "< Parent" typo -- catch it
            // here rather than silently dropping the clabjects, which is
            // what happens below since the whole clabject-processing block
            // only runs when hasParent is true.
            if (!hasParent && !level.clabjects().isEmpty()) {
                throw new Exception("Level " + level.name().getText() +
                        " declares clabject(s) but has no parent level (missing '< ParentLevel'?)");
            }

            // Rule 1: category -> class (isCategory is dropped here; it
            // was only ever a hint for this desugarer).
            List<ASTClass> allClasses = new ArrayList<>(level.classifiers());

            // Clabjects in CatMLM may name a class that was never given
            // its own "class"/"category" body (e.g. "clabject E < D;
            // E : B2 end" with no separate declaration of E).
            // Synthesize an empty one, same as MLMUse's own
            // "class E < D end" would.
            for (ASTCatClabject cc : level.clabjects()) {
                String childName = cc.name().getText();
                if (!ownClasses.containsKey(childName)) {
                    ASTClass synthesized = new ASTClass(cc.name(), false);
                    ownClasses.put(childName, synthesized);
                    allClasses.add(synthesized);
                }
                if (cc.localParent() != null) {
                    List<Token> superIds = new ArrayList<>();
                    superIds.add(cc.localParent());
                    ownClasses.get(childName).addSuperClassifiers(superIds);
                }
            }

            ASTModel model = new ASTModel(level.name());
            for (ASTClass c : allClasses) model.addClass(c);
            for (ASTAssociation a : level.associations()) model.addAssociation(a);
            for (ASTConstraintDefinition c : level.constraints()) model.addConstraint(c);
            multiModel.addModel(model);

            // Rule 2: "model ID1 < ID2"    -> "mediator ID1 < ID2"
            //         "model ID1 < NONE"   -> "mediator ID1 < NONE"
            //         "model ID1" (no <)   -> "mediator ID1 < NONE"
            Token parentToken = hasParent
                    ? level.parentName()
                    : new CommonToken(level.name().getType(), "NONE");
            ASTMediator mediator = new ASTMediator(level.name(), parentToken);

            if (hasParent) {
                Map<String, ASTClass> parentClasses = classesByLevel.get(level.parentName().getText());
                if (parentClasses == null) {
                    throw new Exception("Level " + level.name().getText() +
                            " declares parent " + level.parentName().getText() + ", which was not found");
                }

                for (ASTCatClabject cc : level.clabjects()) {
                    // Rule 3: one clabject per listed powerclass.
                    for (Token powerclassTok : cc.powerclasses()) {
                        ASTClass powerclass = parentClasses.get(powerclassTok.getText());
                        if (powerclass == null) {
                            throw new Exception("Powerclass " + powerclassTok.getText() +
                                    " not found in level " + level.parentName().getText());
                        }

                        ASTClabject clabject = new ASTClabject(cc.name(), powerclassTok);

                        // Rule 4: auto-cancel every cat*-tagged feature of
                        // the powerclass -- attributes, constraints, and
                        // (via catAssociation) roles -- including ones it
                        // only has via its own same-level superclass chain.
                        ASTCatLevel parentLevel = levelsByName.get(level.parentName().getText());
                        Set<String> visited = new HashSet<>();
                        collectCategoryOnlyCancellations(powerclass, parentClasses,
                                parentLevel.associations(), parentLevel.constraints(), visited, clabject);

                        mediator.addClabject(clabject);
                    }
                }
            }

            mlm.addMediator(mediator);
        }

        // Rule 5: genuine inter-level associations/constraints (declared
        // outside every catLevel, e.g. "inter-associations ... end") pass
        // straight through onto the ASTMultiModel -- these are already the
        // exact AST types (ASTAssociation/ASTConstraintDefinition/ASTPrePost)
        // multi_model_core's own grammar actions add here for plain
        // MLM-USE, so no translation is needed, only forwarding. Model@Class
        // resolution, well-definedness checking, etc. are inherited for
        // free once this AST shape matches.
        for (ASTAssociation ia : cat.interAssociations()) {
            multiModel.addInterAssociation(ia);
        }
        for (ASTConstraintDefinition ic : cat.interConstraints()) {
            multiModel.addConstraint(ic);
        }
        for (ASTPrePost ip : cat.interPrePosts()) {
            multiModel.addPrePost(ip);
        }

        mlm.addMultiModel(multiModel);
        return mlm;
    }

    private static void collectCategoryOnlyCancellations(ASTClass cls,
                                                           Map<String, ASTClass> levelClasses,
                                                           List<ASTAssociation> levelAssociations,
                                                           List<ASTConstraintDefinition> levelConstraints,
                                                           Set<String> visited,
                                                           ASTClabject clabject) {
        if (!visited.add(cls.getName().getText())) return; // avoid cycles

        String clsName = cls.getName().getText();

        for (ASTAttribute attr : cls.fAttributes) {
            if (attr.isCategoryOnly()) {
                clabject.addAttributeRemoving(attr.nameToken());
            }
        }
        // Class-body constraints ("category A ... constraints inv X: ... end").
        for (ASTInvariantClause inv : cls.fInvariantClauses) {
            if (inv.isCategoryOnly() && inv.nameToken() != null) {
                clabject.addConstraintRemoving(inv.nameToken());
            }
        }
        // Model-level constraints ("constraints context A inv X: ..."),
        // the form actually used by the ABCD worked example -- matched
        // by the class name the "context" clause names.
        for (ASTConstraintDefinition constraintDef : levelConstraints) {
            ASTType type = constraintDef.type();
            if (!(type instanceof ASTSimpleType)) continue;
            if (!((ASTSimpleType) type).nameToken().getText().equals(clsName)) continue;

            for (ASTInvariantClause inv : constraintDef.invariantClauses()) {
                if (inv.isCategoryOnly() && inv.nameToken() != null) {
                    clabject.addConstraintRemoving(inv.nameToken());
                }
            }
        }

        // catAssociation: for every tagged association with an end
        // targeting this class, cancel the *other* end(s)' role name(s)
        // -- the role(s) you'd use to navigate away from this class.
        // This is what prevents two powerclasses' associations from
        // handing a multi-powerclass clabject two roles with the same
        // name (e.g. two different "r1"s).
        for (ASTAssociation assoc : levelAssociations) {
            if (!assoc.isCategoryOnly()) continue;
            boolean touchesCls = assoc.getEnds().stream()
                    .anyMatch(e -> e.getClassName().equals(clsName));
            if (!touchesCls) continue;

            for (ASTAssociationEnd end : assoc.getEnds()) {
                if (!end.getClassName().equals(clsName) && end.getRolename() != null) {
                    clabject.addRoleRemoving(end.getRolename());
                }
            }
        }

        if (cls.fSuperClassifiers != null) {
            for (Token superTok : cls.fSuperClassifiers) {
                ASTClass superClass = levelClasses.get(superTok.getText());
                if (superClass != null) {
                    collectCategoryOnlyCancellations(superClass, levelClasses, levelAssociations,
                            levelConstraints, visited, clabject);
                }
            }
        }
    }
}
