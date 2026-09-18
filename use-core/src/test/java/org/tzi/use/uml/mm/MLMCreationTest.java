package org.tzi.use.uml.mm;

import junit.framework.TestCase;
import org.tzi.use.parser.use.USECompilerMLM;
import org.tzi.use.uml.Definedness;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class MLMCreationTest extends TestCase {


    public void testCreateMLMWithEmptyMediators() {
        MMultiLevelModel multiLevelModel = TestMLMUtil.getInstance().createMLMWithEmptyMediator();
        assertEquals("Mediator1", multiLevelModel.getMediator("Mediator1").name());
        assertEquals("Mediator2", multiLevelModel.getMediator("Mediator2").name());

    }

    public void testCreateMLMWithClabjects_AttributeRenaming() {
        MMultiLevelModel multiLevelModel = TestMLMUtil.getInstance().createMLMWithClabjects_AttributeRenaming();
        MClabject clabject = multiLevelModel.getClabject("Mediator2", "CLABJECT___PersonCompany2@Person___PersonCompany1@Person");
        assertEquals(1, clabject.getAttributes().size());

        MAttribute originalAtr = clabject.child().attribute("name", false);
        MAttribute renamedAtr =  multiLevelModel.getClass("PersonCompany2", "Person").attribute("newName", false);

        assertEquals(originalAtr, renamedAtr);
    }

    public void testCreateMLMWithClabjects_AttributeRemoving() {
        MMultiLevelModel multiLevelModel = TestMLMUtil.getInstance().createMLMWithClabjects_AttributeRenaming();
        MClabject clabject = multiLevelModel.getClabject("Mediator2", "CLABJECT___PersonCompany2@Person___PersonCompany1@Person");
        assertEquals(1, clabject.getAttributes().size());

        MAttribute renamedAtr =  multiLevelModel.getClass("PersonCompany2", "Person").attribute("name", false);
        assertNull(renamedAtr);
    }

    public void testValidMLM1(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM1();
        assertFalse(mlm.checkState());
    }
    public void testValidMLM2(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM2();
        assertFalse(mlm.checkState());
    }
    public void testValidMLM3(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM3();
        assertTrue(mlm.checkState());
    }

    public void testValidMLM4(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM4();
        assertFalse(mlm.checkState());
    }

    public void testValidMLM_TwoMultiplicityRange_PartiallyLegal() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMTwoMultiplicityRange();
        assertEquals(Definedness.WellDefined.toString(), mlm.checkWellDefinednessState());
    }

    public void testLegalMLM_NoAssocLink_PartiallyLegal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM1();
        assertEquals(Definedness.WellDefined.toString(), mlm.checkWellDefinednessState());
    }
    public void testLegalMLM_OneAssocLink_PartiallyLegal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM2();
        assertEquals(Definedness.WellDefined.toString(), mlm.checkWellDefinednessState());
    }
    public void testLegalMLM_TwoAssocLink_Legal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM3();
        assertEquals(Definedness.WellDefined.toString(), mlm.checkWellDefinednessState());
    }

    public void testLegalMLM_ThreeAssocLink_Illegal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM4();
        assertEquals(Definedness.NotWellDefined.toString(), mlm.checkWellDefinednessState());
    }

    public void testLegalMLM_Constraint_Illegal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithConstraint();
        assertEquals(Definedness.NotWellDefined.toString(), mlm.checkWellDefinednessState());
    }

    public void testLegalMLM_Constraint_Legal(){
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithConstraint2();
        assertEquals(Definedness.WellDefined.toString(), mlm.checkWellDefinednessState());
    }

    public void testMLM_AttributeRenaming() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithAttributeRenaming();
        MClabject clabject = mlm.getClabject("CD", "CLABJECT___CD@C___AB@A");
        String newName = clabject.getRenamedAttribute("name").newName();

        assertEquals("newName", newName);
    }
    public void testMLM_AttributeRenaming_existingNewName() {
        try{
            MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithAttributeRenaming2();
            fail("Should throw exception");
        } catch(Exception e) {
            assertEquals("Attribute: newName already exists", e.getMessage());
        }
    }

    public void testMLM_AttributeRenaming_ThreeLevels_1() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithAttributeRenaming3();
        MClabject clabject = mlm.getClabject("CD", "CLABJECT___CD@C___AB@A");
        String newName = clabject.getRenamedAttribute("aa1").newName();

        assertEquals("aa3", newName);
    }

    /**
     * Regression for MMultiLevelModel.getParentModel(String): it used to
     * infer a model's parent from its position in the declaration-order
     * fModelsList (whatever was added immediately before it), rather than
     * from its actual mediator. "Child" is declared BEFORE "Parent" in the
     * source text here, but its mediator explicitly names "Parent" as its
     * parent -- exactly the shape that fooled the old implementation: it
     * would have returned null for Child (nothing precedes it positionally)
     * and Child itself for Parent (the model added right before it), both
     * wrong. See proposed-changes.md's "levels"-tagged entry for the full
     * writeup.
     */
    public void testGetParentModelUsesMediatorNotDeclarationOrder() {
        String src =
                "MLM ParentDeclaredAfterChild\n" +
                "\n" +
                "model Child\n" +
                "\n" +
                "model Parent\n" +
                "class A\n" +
                "end\n" +
                "\n" +
                "mediator Child < Parent\n" +
                "end\n";

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerMLM.compileMLMSpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "parent-after-child.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("compile failed:\n" + errBuf, mlm);

        assertEquals("Parent", mlm.getParentModel("Child").name());
        assertNull("Parent is the root here, it has no parent of its own",
                mlm.getParentModel("Parent"));
    }

    /** A model with no mediator at all must still return null, not throw. */
    public void testGetParentModelReturnsNullForModelWithNoMediator() {
        String src =
                "MLM LonelyModel\n" +
                "\n" +
                "model Solo\n" +
                "class X\n" +
                "end\n";

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerMLM.compileMLMSpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "lonely.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("compile failed:\n" + errBuf, mlm);

        assertNull(mlm.getParentModel("Solo"));
    }

    /** Preserves the existing behavior for a name that isn't a model at all. */
    public void testGetParentModelReturnsNullForUnknownModel() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM1();
        assertNull(mlm.getParentModel("NoSuchModel"));
    }

    /**
     * Ties the two halves of the fix together: UseMLMApi.createMediator's
     * new three-argument overload (explicit parent, no more inference)
     * actually wires the mediator up correctly, and getParentModel(String)
     * surfaces that same, correct answer afterward.
     */
    public void testCreateMediatorWithExplicitParentIsReflectedByGetParentModel() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLM1();

        assertEquals("AB", mlm.getMediator("CD").getParentModel().name());
        assertEquals("AB", mlm.getParentModel("CD").name());
        assertNull("AB was created with no explicit parent", mlm.getMediator("AB").getParentModel());
    }

    /**
     * Regression for the orderedLevels() triplication fix:
     * MMultiLevelModel now has one real, shared levelsInHierarchyOrder()
     * method instead of three private copies (PlantUmlDiagramGenerator,
     * MMPrintVisitor, FlatUseTextRenderer). "Alpha" sorts alphabetically
     * before "Zebra", but Zebra is the actual root and Alpha its child --
     * demonstrates this is genuinely a different (and correct) ordering
     * from models() alone, not a no-op wrapper around it.
     */
    public void testLevelsInHierarchyOrderIsParentFirstNotAlphabetical() {
        String src =
                "MLM AlphaZebraLevels\n" +
                "\n" +
                "model Alpha\n" +
                "\n" +
                "model Zebra\n" +
                "class A\n" +
                "end\n" +
                "\n" +
                "mediator Alpha < Zebra\n" +
                "end\n";

        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerMLM.compileMLMSpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "alpha-zebra-levels.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("compile failed:\n" + errBuf, mlm);

        List<String> hierarchyOrder = mlm.levelsInHierarchyOrder().stream()
                .map(MModel::name).collect(Collectors.toList());
        assertEquals(List.of("Zebra", "Alpha"), hierarchyOrder);

        List<String> alphabetical = mlm.models().stream()
                .map(MModel::name).collect(Collectors.toList());
        assertEquals(List.of("Alpha", "Zebra"), alphabetical);
    }

    /** A three-level chain sorts correctly by actual depth, not just "has a parent or not". */
    public void testLevelsInHierarchyOrderHandlesThreeLevelChain() {
        MMultiLevelModel mlm = TestMLMUtil.getInstance().createMLMWithAttributeRenaming3();
        List<String> names = mlm.levelsInHierarchyOrder().stream()
                .map(MModel::name).collect(Collectors.toList());
        assertEquals(List.of("AB", "CD", "EF"), names);
    }





}
