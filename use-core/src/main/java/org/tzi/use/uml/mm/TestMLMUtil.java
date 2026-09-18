package org.tzi.use.uml.mm;

import org.tzi.use.api.UseMLMApi;

public class TestMLMUtil {

    private static TestMLMUtil util = null;
    private final String delimiter = "@";
    private final String mlmName = "TestMLM";
    private final String personCompany1 = "PersonCompany1";
    private final String personCompany2 = "PersonCompany2";
    private final String personClass = "Person";
    private final String companyClass = "Company";
    private final String jobAssociation = "Job";


    private TestMLMUtil() {}

    public static TestMLMUtil getInstance() {
        if(util == null) {
            util = new TestMLMUtil();
        }
        return util;
    }

    public MMultiLevelModel createEmptyMLM() {
        UseMLMApi mlmApi = new UseMLMApi(mlmName);
        return mlmApi.getMultiLevelModel();
    }

    public MMultiLevelModel createMLMSimple() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        return mlm;
    }

    public MMultiLevelModel createMLMWithEmptyMediator() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("Mediator1", personCompany1);
            mlmApi.createMediator("Mediator2", personCompany2);
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithClabjects_AttributeRenaming() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("Mediator1", personCompany1);
            mlmApi.createMediator("Mediator2", personCompany2, personCompany1);
            mlmApi.createClabject("Mediator2",personCompany2 + delimiter + personClass, personCompany1 + delimiter +  personClass);
            mlmApi.createAttributeRenaming("Mediator2",
                    "CLABJECT___" + personCompany2 + delimiter + personClass + "___" + personCompany1 + delimiter +  personClass,
                    "name", "newName");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithEmptyClabjects_AttributeRenaming() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("Mediator1", personCompany1);
            mlmApi.createMediator("Mediator2", personCompany2, personCompany1);
            mlmApi.createClabject("Mediator2",personCompany2 + delimiter + personClass, personCompany1 + delimiter +  personClass);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithClabjects_AttributeRemoving() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("Mediator1", personCompany1);
            mlmApi.createMediator("Mediator2", personCompany2, personCompany1);
            mlmApi.createClabject("Mediator2",personCompany2 + delimiter + personClass, personCompany1 + delimiter +  personClass);
            mlmApi.removeAttribute("Mediator2",
                    "CLABJECT___" + personCompany2 + delimiter + personClass + "___" + personCompany1 + delimiter +  personClass,
                    "name");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithMediator_complex_1() {
        return null;
    }

    public MMultiLevelModel createMLMWithMediator_complex_2() {
        return null;
    }

    public MMultiLevelModel createMLMWithThreeMediators() {
        return null;
    }


    public MMultiLevelModel createMLM1() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels3());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLM2() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels3());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLM3() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels4());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLM4() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels5());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");
            mlmApi.createClabject("CD","CD@F", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@cf1", "AB@ab1");

        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMTwoMultiplicityRange() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModelsTwoMultiplicity());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");

        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithConstraint() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels6());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@rcd", "AB@r");

        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithConstraint2() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModels6());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");

        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithAttributeRenaming() {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModelsWithAttributes());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");

            mlmApi.createAttributeRenaming("CD", "CLABJECT___CD@C___AB@A", "name", "newName");
        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithAttributeRenaming2() throws Exception {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModelsWithAttributes2());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("CD","CD@D", "AB@B");
            mlmApi.createClabject("CD","CD@E", "AB@B");

            mlmApi.createAssoclink("CD", "CD@cd1", "AB@ab1");
            mlmApi.createAssoclink("CD", "CD@ce1", "AB@ab1");

            mlmApi.createAttributeRenaming("CD", "CLABJECT___CD@C___AB@A", "address", "newName");
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
        return mlm;
    }

    public MMultiLevelModel createMLMWithAttributeRenaming3()  {
        UseMLMApi mlmApi = new UseMLMApi(TestMultiModelUtil.getInstance().createMultiModelTwoModelsWithAttributes3());
        MMultiLevelModel mlm = mlmApi.getMultiLevelModel();
        try {
            mlmApi.createMediator("AB", "AB");
            mlmApi.createMediator("CD", "CD", "AB");
            mlmApi.createMediator("EF", "EF", "CD");

            mlmApi.createClabject("CD","CD@C", "AB@A");
            mlmApi.createClabject("EF","EF@E", "CD@C");

            //Check if create and remove attributes works properly in the api
            mlmApi.createAttributeRenaming("CD", "CLABJECT___CD@C___AB@A", "aa1", "aa3");
            mlmApi.removeAttribute("CD", "CLABJECT___CD@C___AB@A", "cc");
            mlmApi.createAttributeRenaming("EF", "CLABJECT___EF@E___CD@C", "cc", "aa1");

        } catch (Exception e) {
            throw new Error( e );
        }
        return mlm;
    }

}
