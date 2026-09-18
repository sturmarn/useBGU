package org.tzi.use.api;

import org.tzi.use.uml.mm.*;
import org.tzi.use.util.StringUtil;

import java.util.List;

public class UseMLMApi extends UseMultiModelApi{

    private MMultiLevelModel mMultiLevelModel;
    MultiLevelModelFactory mFactory = new MultiLevelModelFactory();

    /**
     * Creates a new UseMLMApi instance with an empty multi-level-model named "unnamed".
     * The new multi-level-model instance can be retrieved by {@link #getMultiLevelModel()}.
     */
    public UseMLMApi() {
        mMultiLevelModel = mFactory.createMLM("unnamed");
    }

    public UseMLMApi(MMultiModel multiModel) {
        mMultiLevelModel = mFactory.createMLM(multiModel);
    }

    /**
     * Creates a new UseMLMApi instance with an empty multi-level-model named <code>name</code>.
     * The new multi-level-model instance can be retrieved by {@link #getMultiLevelModel()}.
     * @param name The name of the new model.
     */
    public UseMLMApi(String name) {
        mMultiLevelModel = mFactory.createMLM(name);
    }

    /**
     * Creates a new UseMLMApi instance with
     * the provided <code>multiLevelModel</code> as the multi-level-model instance.
     * This is useful if you want to modify an existing multi-level-model instance.
     * @param mlm The multi-level-model to modify through this API instance.
     */
    public UseMLMApi(MMultiLevelModel mlm) {
        mMultiLevelModel = mlm;
    }

    /**
     * Returns the multi-level-model modified through this API instance.
     * @return the multi-level-model handled by this API instance.
     */
    public MMultiLevelModel getMultiLevelModel() {
        return mMultiLevelModel;
    }

    /**
     * Helper method to safely retrieve a class or an inter-class.
     * Safe by the degree, that if no exception is thrown you get a valid class
     * instance. In contrast to the need to handle <code>null</code> as a return value.
     *  <p>
     *      In order to retrieve regular classes from a model the name
     *      must be in the format: <code> modelName@className </code>
     *  </p>
     *  <p>
     *      For inter-classes the name must be the name of the inter-class
     *  </p>
     * @param name The name of the class to lookup.
     * @return The {@link MClass} with the name <code>name</code>.
     * @throws UseApiException If no class with the given name exists in the encapsulated multi-model.
     */
    @Override
    public MClass getClassSafe(String name) throws UseApiException {
        if (!name.contains("@")){
            //inter-class
            MClass cls = mMultiLevelModel.getClass(name);
            if (cls == null) {
                throw new UseApiException("Unknown class " + StringUtil.inQuotes(name));
            }
            return cls;
        }

        //regular class
        String modelName = name.split("@")[0];
        String className = name.split("@")[1];

        MModel model = mMultiLevelModel.getModel(modelName);
        if (model == null){
            throw new UseApiException("Unknown model " + StringUtil.inQuotes(modelName));
        }

        MClass cls = model.getClass(name);
        if (cls == null) {
            throw new UseApiException("Unknown class " + StringUtil.inQuotes(name) +
                    " in model " + StringUtil.inQuotes(modelName));
        }
        return cls;
    }

    /**
     * Creates a new, parentless (root-level) Mediator -- equivalent to
     * plain MLM-USE's own "mediator ID1 &lt; NONE". See the three-argument
     * overload below for a mediator with an actual parent; there is no
     * way to *infer* a parent from {@code relatedModel} alone (nothing
     * about a model's own name or position implies a hierarchy -- see
     * {@link MMultiLevelModel#getParentModel(String)}), so a caller that
     * needs one must say so explicitly.
     *
     * @param mediatorName The name of the new mediator to be created.
     * @param relatedModel The name of the model this mediator belongs to.
     * @return The newly created Mediator object.
     * @throws Exception If the model does not exist.
     */
    public MMediator createMediator(String mediatorName, String relatedModel) throws Exception {
        return createMediator(mediatorName, relatedModel, null);
    }

    /**
     * This method is used to create a new Mediator object and add it to the multi-level model.
     *
     * @param mediatorName The name of the new mediator to be created.
     * @param relatedModel The name of the model this mediator belongs to.
     * @param parentModelName The name of the explicitly-declared parent model, or {@code null} for none (a root level, "&lt; NONE").
     * @return The newly created Mediator object.
     * @throws Exception If the current model, or the named parent model, does not exist.
     */
    public MMediator createMediator(String mediatorName, String relatedModel, String parentModelName) throws Exception {
        MModel currentModel = mMultiLevelModel.getModel(relatedModel);
        if (currentModel == null) {
            throw new Exception("Model " + relatedModel + " is invalid");
        }
        MModel parentModel = null;
        if (parentModelName != null) {
            parentModel = mMultiLevelModel.getModel(parentModelName);
            if (parentModel == null) {
                throw new Exception("Model " + parentModelName + " is invalid");
            }
        }

        MMediator mediator = mFactory.createMediator(mediatorName);
        this.mMultiLevelModel.addMediator(mediator);
        mediator.setCurrentModel(currentModel);
        mediator.setParentModel(parentModel);
        return mediator;
    }
    public void removeMediator(String name){
        this.mMultiLevelModel.removeMediator(name);
    }

    public MMediator getMediator(String name){
        return this.mMultiLevelModel.getMediator(name);
    }

    /**
     * This method is used to create a new Clabject object and add it to the mediator.
     * @param mediatorName The name of the mediator to which the clabject is added.
     * @param childName The name of the child class.
     * @param parentName The name of the parent class.
     * @return The newly created Clabject object.
     */
    public MClabject createClabject(String mediatorName, String childName, String parentName){
        MMediator mediator = this.getMediator(mediatorName);
        if (mediator == null) {
            throw new NullPointerException("Mediator " + mediatorName + " is invalid");
        }

        MClass child = mediator.getCurrentModel().getClass(childName);
        if (child == null) {
            throw new NullPointerException("Child " + childName + " is invalid");
        }

        MClass parent = mediator.getParentModel().getClass(parentName);
        if (parent == null) {
            throw new NullPointerException("Parent " + parentName + " is invalid");
        }


        MClabject clabject = mFactory.createClabject(child, parent);
        mediator.addClabject(clabject);
        return clabject;
    }

    /**
     * This method is used to create a new MAttributeRenaming object and add it to the Clabject.
     * @param mediatorName The name of the mediator that holds the clabject.
     * @param clabjectName The name of the clabject to which the attribute renaming is added.
     * @param oldAttrName The name of the existing attribute.
     * @param newAttrName The name of the new renamed attribute.
     * @return The newly created MAttributeRenaming object.
     */
    public MAttributeRenaming createAttributeRenaming(String mediatorName, String clabjectName, String oldAttrName, String newAttrName){
        MMediator mediator = this.getMediator(mediatorName);
        if (mediator == null) {
            throw new NullPointerException("Mediator " + mediatorName + " is invalid");
        }
        MClabject clabject = mediator.getClabject(clabjectName);
        if (clabject == null) {
            throw new NullPointerException("Clabject " + clabjectName + " is invalid");
        }
        MClassifier parentCls = clabject.parent();
        if (parentCls == null) {
            throw new NullPointerException("Parent class is invalid");
        }
        MAttribute oldAttribute = parentCls.attribute(oldAttrName, true);
        if (oldAttribute == null) {
            throw new NullPointerException("Attribute " + oldAttrName + " is invalid");
        }
        MAttributeRenaming attributeRenaming = new MAttributeRenaming(oldAttribute, newAttrName);
        clabject.addAttributeRenaming(attributeRenaming);
        return attributeRenaming;
    }

    public void removeAttribute(String mediatorName, String clabjectName, String oldAttrName){
        MMediator mediator = this.getMediator(mediatorName);
        MClabject clabject = mediator.getClabject(clabjectName);

        MAttribute attr = clabject.child().attribute(oldAttrName, false);

        clabject.addRemovedAttribute(attr);
    }

    public MAssoclink createAssoclink(String mediatorName, String childName, String parentName){
        MMediator mediator = this.getMediator(mediatorName);
        if (mediator == null) {
            throw new NullPointerException("Mediator " + mediatorName + " is invalid");
        }

        MAssociation child  = mediator.getCurrentModel().getAssociation(childName);
        if (child == null) {
            throw new NullPointerException("Child " + childName + " is invalid");
        }

        MAssociation parent  = mediator.getParentModel().getAssociation(parentName);
        if (parent == null) {
            throw new NullPointerException("Parent " + parentName + " is invalid");
        }

        MAssoclink assoclink = mFactory.createAssoclink(child, parent);
        mediator.addAssocLink(assoclink);
        return assoclink;
    }
}
