package org.tzi.use.parser;

import junit.framework.TestCase;
import org.tzi.use.parser.use.USECompilerCatUSE;
import org.tzi.use.uml.mm.MMultiLevelModel;
import org.tzi.use.uml.mm.MultiLevelModelFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One focused test per CatMLM-to-MLMUse translation rule, complementing
 * the single large ABCD integration test in {@link CatUSESmokeTest}.
 * Each case isolates exactly one rule and avoids the diamond-inheritance
 * conflicts that made the full ABCD example's {@code E < D; E : B2}
 * combination untestable end-to-end.
 */
public class CatUSETranslationRulesTest extends TestCase {

    private MMultiLevelModel compile(String src) {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "catuse-rule-test.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("CatUSE compilation failed:\n" + errBuf, mlm);
        return mlm;
    }

    private Set<String> attrNames(MMultiLevelModel mlm, String model, String cls) {
        return mlm.getClass(model, cls).allAttributes().stream()
                .map(a -> a.name()).collect(Collectors.toSet());
    }

    /** Rule 1: "category ID" is just "class ID" -- no clabject involved at all. */
    public void testCategoryIsPlainClass() {
        MMultiLevelModel mlm = compile(
                "MLM CatVsClass\n" +
                "\n" +
                "model M1 < NONE\n" +
                "category A\n" +
                "attributes\n" +
                "attr1: Integer\n" +
                "end\n" +
                "\n" +
                "class B\n" +
                "attributes\n" +
                "attr2: String\n" +
                "end\n");

        assertEquals(Set.of("attr1"), attrNames(mlm, "M1", "A"));
        assertEquals(Set.of("attr2"), attrNames(mlm, "M1", "B"));
    }

    /** Rule 4a: a catAtt-tagged attribute is cancelled in every instantiating clabject. */
    public void testCatAttCancelsAttribute() {
        MMultiLevelModel mlm = compile(
                "MLM CatAttSimple\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Animal\n" +
                "attributes\n" +
                "name: String; catAtt\n" +
                "legs: Integer\n" +
                "end\n" +
                "\n" +
                "model Instances < Meta\n" +
                "clabject Dog : Animal\n" +
                "end\n");

        assertEquals(Set.of("legs"), attrNames(mlm, "Instances", "Dog"));
    }

    /** Rule 4b: a catConstr-tagged model-level constraint is cancelled the same way. */
    public void testCatConstrCancelsConstraint() {
        MMultiLevelModel mlm = compile(
                "MLM CatConstrSimple\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Animal\n" +
                "attributes\n" +
                "legs: Integer\n" +
                "end\n" +
                "\n" +
                "constraints\n" +
                "context Animal inv LegsPositive: catConstr\n" +
                "self.legs > 0\n" +
                "\n" +
                "model Instances < Meta\n" +
                "clabject Dog : Animal\n" +
                "end\n");

        boolean stillHasIt = mlm.allClassInvariants(mlm.getClass("Instances", "Dog")).stream()
                .anyMatch(inv -> inv.name().contains("LegsPositive"));
        assertFalse("catConstr-tagged invariant should be cancelled for the clabject", stillHasIt);
    }

    /**
     * Rule 4c: a catAssociation-tagged association has its far-end role
     * cancelled for a clabject instantiating the near end -- here with a
     * single powerclass, isolating the rule from the multi-powerclass
     * role-collision scenario ABCD needed it for.
     */
    public void testCatAssociationCancelsFarEndRole() {
        MMultiLevelModel mlm = compile(
                "MLM CatAssocSimple\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Animal\n" +
                "end\n" +
                "\n" +
                "class Habitat\n" +
                "end\n" +
                "\n" +
                "catAssociation livesIn between\n" +
                "Animal[1] role home\n" +
                "Habitat[*] role residents\n" +
                "end\n" +
                "\n" +
                "model Instances < Meta\n" +
                "clabject Dog : Animal\n" +
                "end\n");

        assertFalse("the far-end role 'residents' should be cancelled for Dog",
                mlm.getClass("Instances", "Dog").navigableEnds().containsKey("residents"));
    }

    /** Rule 3: "clabject C : (A, B)" expands into one clabject per powerclass. */
    public void testMultiPowerclassClabjectCombinesBoth() {
        MMultiLevelModel mlm = compile(
                "MLM MultiPowerclassSimple\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Vehicle\n" +
                "attributes\n" +
                "wheels: Integer\n" +
                "end\n" +
                "\n" +
                "category Weapon\n" +
                "attributes\n" +
                "damage: Integer\n" +
                "end\n" +
                "\n" +
                "model Instances < Meta\n" +
                "clabject Tank : (Vehicle, Weapon)\n" +
                "end\n");

        assertEquals(Set.of("wheels", "damage"), attrNames(mlm, "Instances", "Tank"));
    }

    /**
     * The fused "X < Y; X : Z" shorthand: X should carry both
     * Y's (same-level) and Z's (instance-of) attributes, chosen here with
     * disjoint attribute names so it can succeed without hitting the
     * dual-inheritance-diamond case discussed separately.
     */
    public void testFusedLocalSubclassAndClabject() {
        MMultiLevelModel mlm = compile(
                "MLM FusedSubclassSimple\n" +
                "\n" +
                "model Meta < NONE\n" +
                "category Item\n" +
                "attributes\n" +
                "price: Integer\n" +
                "end\n" +
                "\n" +
                "model Instances < Meta\n" +
                "class Product\n" +
                "attributes\n" +
                "sku: String\n" +
                "end\n" +
                "\n" +
                "clabject SpecialProduct < Product; SpecialProduct : Item\n" +
                "end\n");

        assertEquals(Set.of("sku", "price"), attrNames(mlm, "Instances", "SpecialProduct"));
    }

    /**
     * Regression: a clabject declared in a level with no parent used to be
     * silently dropped -- the whole clabject-processing block only ran
     * inside "if (hasParent)". It must now be rejected with a clear error
     * instead of compiling successfully with the clabject just missing.
     */
    public void testClabjectInParentlessLevelIsRejectedNotDropped() {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream((
                        "MLM NoParentClabject\n" +
                        "\n" +
                        "model Meta < NONE\n" +
                        "category A\n" +
                        "end\n" +
                        "\n" +
                        "model Orphan\n" +
                        "clabject C : A\n" +
                        "end\n").getBytes(StandardCharsets.UTF_8)),
                "catuse-orphan-clabject.use", err, new MultiLevelModelFactory());
        err.flush();

        assertNull("a clabject with no parent level to instantiate from must not silently compile away", mlm);
        assertTrue("the error should point at the missing '< Parent', not just fail silently",
                errBuf.toString().contains("no parent level"));
    }

    /** Rule 2: an omitted "< PARENT" and an explicit "< NONE" are equivalent top-level markers. */
    public void testBareModelWithNoParentIsTopLevel() {
        MMultiLevelModel mlm = compile(
                "MLM BareModelSimple\n" +
                "\n" +
                "model Meta\n" +
                "category Base\n" +
                "attributes\n" +
                "x: Integer\n" +
                "end\n");

        assertEquals(Set.of("x"), attrNames(mlm, "Meta", "Base"));
    }

    /**
     * Genuine, still-correctly-rejected conflict: catAssociation cancellation
     * is opt-in per association. Here neither {@code aa1} nor {@code bb1} is
     * tagged, so a clabject instantiating both A and B still collides on the
     * shared role name "r1" -- exactly the same failure the untagged ABCD
     * example hit before catAssociation was applied to it. Loaded from the
     * matching .use/.fail fixture pair under {@code org/tzi/use/catUseTests},
     * mirroring how the base MLM-USE suite pairs its own fixtures.
     */
    public void testUntaggedAssociationsStillConflict() throws URISyntaxException, IOException {
        File dir = new File(ClassLoader.getSystemResource("org/tzi/use/catUseTests").toURI());
        File useFile = new File(dir, "CatAssociation_untagged_role_conflict.use");
        File failFile = new File(dir, "CatAssociation_untagged_role_conflict.fail");

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm;
        try (FileInputStream in = new FileInputStream(useFile)) {
            mlm = USECompilerCatUSE.compileCatUSESpecification(
                    in, useFile.getName(), err, new MultiLevelModelFactory());
        }
        err.flush();

        assertNull("two untagged associations sharing a role name must still be rejected", mlm);

        String expected = new String(java.nio.file.Files.readAllBytes(failFile.toPath()), StandardCharsets.UTF_8).strip();
        assertEquals(expected, errBuf.toString().strip());
    }

    /**
     * Rule 5: a genuine "inter-associations" section (outside every
     * catLevel) desugars straight through onto the ASTMultiModel, exactly
     * as it would for plain MLM-USE -- direct translation of
     * bookshop_corrected.pl's "catShop" fact, the case that motivated
     * adding this section at all.
     */
    public void testInterAssociationCrossesLevels() {
        MMultiLevelModel mlm = compile(
                "MLM InterAssocSimple\n" +
                "\n" +
                "model Catalog < NONE\n" +
                "category catalog\n" +
                "end\n" +
                "category book\n" +
                "end\n" +
                "\n" +
                "model BookShop < Catalog\n" +
                "category bookShop\n" +
                "end\n" +
                "clabject bookCopy : book\n" +
                "end\n" +
                "\n" +
                "inter-associations\n" +
                "association catShop between\n" +
                "Catalog@catalog[1] role catalog\n" +
                "BookShop@bookShop[*] role bookShop\n" +
                "end\n");

        Set<String> interAssocNames = mlm.interAssociations().stream()
                .map(a -> a.name()).collect(Collectors.toSet());
        assertEquals(Set.of("catShop"), interAssocNames);
    }

    /** Rule 5: "inter-constraints" crosses levels the same way. */
    public void testInterConstraintCrossesLevels() {
        MMultiLevelModel mlm = compile(
                "MLM InterConstrSimple\n" +
                "\n" +
                "model Catalog < NONE\n" +
                "category catalog\n" +
                "end\n" +
                "\n" +
                "model BookShop < Catalog\n" +
                "category bookShop\n" +
                "end\n" +
                "\n" +
                "inter-associations\n" +
                "association catShop between\n" +
                "Catalog@catalog[1] role catalog\n" +
                "BookShop@bookShop[*] role bookShop\n" +
                "end\n" +
                "\n" +
                "inter-constraints\n" +
                "context Catalog@catalog inv NonEmptyCatalog:\n" +
                "self.bookShop->notEmpty()\n");

        boolean hasIt = mlm.interInvariants().stream()
                .anyMatch(inv -> inv.name().contains("NonEmptyCatalog"));
        assertTrue("inter-constraints invariant should reach the multi-level model", hasIt);
    }

    /**
     * A bare (non-multiType) class reference inside "inter-associations"
     * must still be rejected, the same way it already is for plain
     * MLM-USE -- proves reusing interAssociationEnd unchanged didn't
     * accidentally loosen that existing hard-error rule.
     */
    public void testInterAssociationRejectsBareClassName() {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream((
                        "MLM InterAssocBareNameFail\n" +
                        "\n" +
                        "model Catalog < NONE\n" +
                        "category catalog\n" +
                        "end\n" +
                        "\n" +
                        "model BookShop < Catalog\n" +
                        "category bookShop\n" +
                        "end\n" +
                        "\n" +
                        "inter-associations\n" +
                        "association catShop between\n" +
                        "catalog[1] role catalog\n" +
                        "BookShop@bookShop[*] role bookShop\n" +
                        "end\n").getBytes(StandardCharsets.UTF_8)),
                "catuse-interassoc-barename.use", err, new MultiLevelModelFactory());
        err.flush();

        assertNull("a bare class name inside inter-associations must still be rejected", mlm);
    }

    /**
     * "inter-classes" has no grammar rule in CatMLM at all -- deliberately
     * excluded (known-issues.org, Issue 9). Confirms the exclusion is
     * enforced by the parser, not just by convention/documentation.
     */
    public void testInterClassesSectionIsRejected() {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream((
                        "MLM InterClassesRejected\n" +
                        "\n" +
                        "model Catalog < NONE\n" +
                        "category catalog\n" +
                        "end\n" +
                        "\n" +
                        "inter-classes\n" +
                        "class Foo\n" +
                        "end\n").getBytes(StandardCharsets.UTF_8)),
                "catuse-interclasses-rejected.use", err, new MultiLevelModelFactory());
        err.flush();

        assertNull("inter-classes must not parse at all in CatMLM", mlm);
    }

    /**
     * "catAssociation" has no place inside "inter-associations" -- that
     * section reuses plain MLM-USE's interAssociationDefinition, which
     * only ever accepts association/aggregation/composition. Confirms an
     * inter-association genuinely carries no cancellation semantics,
     * rather than silently accepting the keyword and ignoring it.
     */
    public void testInterAssociationHasNoCatAssociationForm() {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerCatUSE.compileCatUSESpecification(
                new ByteArrayInputStream((
                        "MLM InterAssocNoCatFlavor\n" +
                        "\n" +
                        "model Catalog < NONE\n" +
                        "category catalog\n" +
                        "end\n" +
                        "\n" +
                        "model BookShop < Catalog\n" +
                        "category bookShop\n" +
                        "end\n" +
                        "\n" +
                        "inter-associations\n" +
                        "catAssociation catShop between\n" +
                        "Catalog@catalog[1] role catalog\n" +
                        "BookShop@bookShop[*] role bookShop\n" +
                        "end\n").getBytes(StandardCharsets.UTF_8)),
                "catuse-interassoc-nocatflavor.use", err, new MultiLevelModelFactory());
        err.flush();

        assertNull("catAssociation must not parse inside inter-associations", mlm);
    }
}
