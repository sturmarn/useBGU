package org.tzi.use.tools.catuselatex;

import junit.framework.TestCase;
import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.parser.use.USECompilerCatUSE;
import org.tzi.use.parser.use.USECompilerMLM;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.MMultiLevelModel;
import org.tzi.use.uml.mm.ModelFactory;
import org.tzi.use.uml.mm.MultiLevelModelFactory;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Regression tests for {@link FlatUseTextRenderer}: the third pipeline
 * stage (CatUSE -&gt; MLM-USE -&gt; plain USE), which flattens every class's
 * fully resolved (post-cancellation) structure into ordinary, inheritance-
 * free plain-USE syntax.
 */
public class FlatUseTextRendererTest extends TestCase {

    private static final String CATMLM_VEHICLE =
            "MLM VehicleCatalog\n" +
            "\n" +
            "model Taxonomy < NONE\n" +
            "category Vehicle\n" +
            "attributes\n" +
            "wheels: Integer\n" +
            "internalCode: String; catAtt\n" +
            "end\n" +
            "\n" +
            "category Product\n" +
            "attributes\n" +
            "listPrice: Integer\n" +
            "skuPrefix: String; catAtt\n" +
            "end\n" +
            "\n" +
            "catAssociation classifiedAs between\n" +
            "Vehicle[1] role vehicleSide\n" +
            "Product[1] role productSide\n" +
            "end\n" +
            "\n" +
            "constraints\n" +
            "context Vehicle inv PositiveWheels: catConstr\n" +
            "self.wheels > 0\n" +
            "\n" +
            "model Fleet < Taxonomy\n" +
            "clabject Car : (Vehicle, Product)\n" +
            "end\n" +
            "\n" +
            "clabject Bicycle : Vehicle\n" +
            "end\n";

    private MMultiLevelModel compileCatUse(String src) {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "flat-renderer-test.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("CatUSE compilation failed:\n" + errBuf, mlm);
        return mlm;
    }

    /**
     * The core promise: the flattened text is genuinely plain, vanilla
     * USE -- accepted by USECompiler (not USECompilerMLM), with no MLM
     * syntax (no "MLM" header, no "clabject"/"mediator" left over).
     */
    public void testFlattenedTextIsPlainReparseableUse() {
        String rendered = FlatUseTextRenderer.render(compileCatUse(CATMLM_VEHICLE), "VehicleCatalog");

        assertFalse(rendered.contains("clabject"));
        assertFalse(rendered.contains("mediator"));
        assertFalse(rendered.startsWith("MLM"));

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MModel reparsed = USECompiler.compileSpecification(
                new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)),
                "flat.use", err, new ModelFactory());
        err.flush();
        assertNotNull("flattened text failed to re-parse as plain USE:\n" + errBuf + "\n---\n" + rendered, reparsed);
    }

    /**
     * The whole point of not using "class X < Y" here: a cancelled
     * (catAtt) attribute must be genuinely absent from the clabject's
     * flat attribute list, not just hidden behind inheritance.
     */
    public void testCancelledAttributesAreGenuinelyAbsentFromClabject() {
        String rendered = FlatUseTextRenderer.render(compileCatUse(CATMLM_VEHICLE), "VehicleCatalog");
        int carStart = rendered.indexOf("class Car");
        int carEnd = rendered.indexOf("end", carStart);
        String carBlock = rendered.substring(carStart, carEnd);

        assertTrue(carBlock.contains("wheels"));
        assertTrue(carBlock.contains("listPrice"));
        assertFalse("catAtt-cancelled internalCode must not appear on Car", carBlock.contains("internalCode"));
        assertFalse("catAtt-cancelled skuPrefix must not appear on Car", carBlock.contains("skuPrefix"));
    }

    /** catConstr cancellation: the invariant must print once, only under its surviving owner. */
    public void testCatConstrInvariantOnlyAppearsOnceUnderItsOrigin() {
        String rendered = FlatUseTextRenderer.render(compileCatUse(CATMLM_VEHICLE), "VehicleCatalog");
        assertTrue(rendered.contains("context Vehicle inv PositiveWheels"));
        assertFalse(rendered.contains("context Car inv PositiveWheels"));
        assertFalse(rendered.contains("context Bicycle inv PositiveWheels"));
        assertEquals(1, countOccurrences(rendered, "inv PositiveWheels"));
    }

    /** catAssociation cancellation: neither far-end role survives on the dual-powerclass clabject. */
    public void testCatAssociationRolesAreCancelledOnClabject() {
        String rendered = FlatUseTextRenderer.render(compileCatUse(CATMLM_VEHICLE), "VehicleCatalog");
        assertFalse(rendered.contains("Car also inherits navigable role"));
        assertTrue("Bicycle only instantiates Vehicle, so it loses productSide without ever gaining it",
                !rendered.contains("Bicycle also inherits navigable role(s) productSide"));
    }

    /**
     * A clabject edge that renames/removes nothing at all (CatMLM's
     * {@code clabject X : Y} has no body beyond the powerclass list, so it
     * can never do otherwise -- see VehicleTaxonomy.use's own doc comment)
     * is a safe pass-through: Dog gets a genuine "class Dog &lt; Animal"
     * header (safePassThroughParents), so "residents" -- a role declared
     * on Animal, never cancelled -- arrives automatically via plain-USE
     * inheritance, with no duplicated association block and no
     * "also inherits" comment needed (it's actually reachable, not just
     * documented as unreachable).
     */
    public void testSafePassThroughEdgeInheritsRoleViaPlainSubclassing() {
        String src =
                "MLM InheritedRoleDemo\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Animal\n" +
                "end\n" +
                "\n" +
                "class Habitat\n" +
                "end\n" +
                "\n" +
                "association livesIn between\n" +
                "Animal[1] role home\n" +
                "Habitat[*] role residents\n" +
                "end\n" +
                "\n" +
                "model Instances < Meta\n" +
                "clabject Dog : Animal\n" +
                "end\n";

        String rendered = FlatUseTextRenderer.render(compileCatUse(src), "InheritedRoleDemo");
        assertTrue(rendered.contains("class Dog < Animal"));
        assertFalse("residents is genuinely reachable via '<' now, so no 'also inherits' comment is needed",
                rendered.contains("Dog also inherits navigable role"));
        assertEquals(1, countOccurrences(rendered, "association livesIn"));

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MModel reparsed = USECompiler.compileSpecification(
                new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)),
                "flat.use", err, new ModelFactory());
        err.flush();
        assertNotNull("flattened text with a safe pass-through subclass failed to re-parse:\n" + errBuf, reparsed);
    }

    /**
     * CatMLM's {@code clabject} has no renaming/cancellation mechanism at
     * all (see the test above), so the "stillUnrestated" comment path --
     * a multi-type (non-self) association role inherited through a
     * genuinely renaming/cancelling clabject edge, ineligible for both
     * safePassThroughParents' "&lt;" and printAssociationRetyped's
     * self-association restatement -- can only be reached via MLM-USE's
     * own richer "mediator ... clabject X : Y attributes a -&gt; b end"
     * syntax, so this uses USECompilerMLM directly instead of CatUSE.
     */
    public void testUnrestatableRoleThroughCancellingEdgeIsNotedNotDuplicated() {
        String src =
                "MLM InheritedRoleDemo\n" +
                "\n" +
                "model Meta\n" +
                "class Animal\n" +
                "attributes\n" +
                "legs: Integer\n" +
                "end\n" +
                "\n" +
                "class Habitat\n" +
                "end\n" +
                "\n" +
                "association livesIn between\n" +
                "Animal[1] role home\n" +
                "Habitat[*] role residents\n" +
                "end\n" +
                "\n" +
                "model Instances\n" +
                "class Dog\n" +
                "end\n" +
                "\n" +
                "mediator Meta < NONE\n" +
                "end\n" +
                "\n" +
                "mediator Instances < Meta\n" +
                "clabject Dog : Animal\n" +
                "attributes\n" +
                "legs -> paws\n" +
                "end\n" +
                "end\n";

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerMLM.compileMLMSpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "mlm-renderer-test.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("MLM-USE compilation failed:\n" + errBuf, mlm);

        String rendered = FlatUseTextRenderer.render(mlm, "InheritedRoleDemo");
        assertFalse("the renaming edge rules Dog out of safePassThroughParents, so it must not get 'class Dog < Animal'",
                rendered.contains("class Dog < Animal"));
        assertTrue(rendered.contains("Dog also inherits navigable role(s) residents"));
        assertEquals(1, countOccurrences(rendered, "association livesIn"));

        StringWriter errBuf2 = new StringWriter();
        PrintWriter err2 = new PrintWriter(errBuf2);
        MModel reparsed = USECompiler.compileSpecification(
                new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)),
                "flat.use", err2, new ModelFactory());
        err2.flush();
        assertNotNull("flattened text with an inherited-role comment failed to re-parse:\n" + errBuf2 + "\n---\n" + rendered, reparsed);
    }

    /**
     * Two different levels are allowed to declare a class with the same
     * short name -- that's exactly why classes are named "Level@Class"
     * internally in the first place. Flattening must not silently collapse
     * them into two identically-named plain-USE classes (a genuine bug
     * found while discussing this renderer: it used to do exactly that,
     * producing a "Redefinition of X" failure on re-parse). The colliding
     * pair must instead come out as "Item__AT__M3"/"Item__AT__M2" -- class
     * name first, level name last -- and still re-parse.
     */
    public void testCollidingShortNamesAreDisambiguatedClassFirst() {
        String src =
                "MLM CollisionDemo\n" +
                "\n" +
                "model M3\n" +
                "category Item\n" +
                "attributes\n" +
                "a3: Integer\n" +
                "end\n" +
                "\n" +
                "model M2 < M3\n" +
                "category Item\n" +
                "attributes\n" +
                "a2: Integer\n" +
                "end\n" +
                "\n" +
                "clabject Item : Item\n" +
                "end\n";

        String rendered = FlatUseTextRenderer.render(compileCatUse(src), "CollisionDemo");
        assertTrue(rendered.contains("class Item__AT__M3"));
        assertTrue(rendered.contains("class Item__AT__M2"));
        assertFalse("bare 'class Item' must not appear once the name is ambiguous",
                rendered.contains("class Item\n"));

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MModel reparsed = USECompiler.compileSpecification(
                new ByteArrayInputStream(rendered.getBytes(StandardCharsets.UTF_8)),
                "flat.use", err, new ModelFactory());
        err.flush();
        assertNotNull("disambiguated flattened text failed to re-parse:\n" + errBuf + "\n---\n" + rendered, reparsed);
    }

    /** A short name unique across the whole flattened model stays bare -- no gratuitous "__AT__" noise. */
    public void testUniqueShortNamesStayBare() {
        String rendered = FlatUseTextRenderer.render(compileCatUse(CATMLM_VEHICLE), "VehicleCatalog");
        assertFalse(rendered.contains("__AT__"));
    }

    /**
     * "__AT__" is reserved for this renderer's own synthesized names. Exactly
     * two underscores on both sides of "AT" is rejected; an extra underscore
     * on either side is not the reserved token and must be accepted.
     */
    public void testReservedAtTokenIsRejectedOnlyWhenExact() {
        assertReservedTokenRejected("this__AT__is_not");
        assertNameAccepted("this___AT__is_ok");
        assertNameAccepted("this__AT___is_ok");
    }

    private void assertReservedTokenRejected(String className) {
        try {
            FlatUseTextRenderer.render(compileCatUse(
                    "MLM T\n\nmodel M\nclass " + className + "\nend\n"), "T");
            fail("expected an IllegalArgumentException for the reserved name \"" + className + "\"");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("__AT__"));
        }
    }

    private void assertNameAccepted(String className) {
        String rendered = FlatUseTextRenderer.render(compileCatUse(
                "MLM T\n\nmodel M\nclass " + className + "\nend\n"), "T");
        assertTrue(rendered.contains("class " + className));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
