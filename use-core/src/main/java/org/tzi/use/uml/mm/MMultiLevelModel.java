package org.tzi.use.uml.mm;

import org.tzi.use.api.UseMLMSystemApi;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.api.impl.UseSystemApiUndoable;
import org.tzi.use.uml.Definedness;
import org.tzi.use.uml.Satisfiability;
import org.tzi.use.uml.ocl.type.EnumType;
import org.tzi.use.util.NullPrintWriter;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class MMultiLevelModel extends MMultiModel {

    private final List<MModel> fModelsList; //ordered list of models
    private final Map<String, MMediator> fMediators;

    protected MMultiLevelModel(String name) {
        super(name);
        fModelsList = new ArrayList<>();
        fMediators = new HashMap<>();
    }

    protected MMultiLevelModel(MMultiModel multiModel){
        this(multiModel.name(), multiModel);
    }

    /**
     * @param name the MLM's own declared name (e.g. "ABCD" from "MLM
     *  ABCD"). Must be passed explicitly rather than trusting
     *  {@code multiModel.name()}: {@code multiModel} is built by the
     *  grammar's inner {@code multi_model_core} rule, which keeps its
     *  hard-coded placeholder name ("MLM") unless the source uses the
     *  rarely-used optional "multi_model IDENT" clause -- so
     *  {@code multiModel.name()} is usually just "MLM", not the name the
     *  user actually declared.
     */
    protected MMultiLevelModel(String name, MMultiModel multiModel){
        super(name);
        fModelsList = new ArrayList<>();

        //steal all the fields from the multiModel
        try {
            for (EnumType enumType : multiModel.enumTypes()) {
                this.addEnumType(enumType);
            }
            for (MModel model : multiModel.models()) {
                this.addModel(model);
            }
            for (MClass mClass: multiModel.interClasses()) {
                this.addClass(mClass);
            }
            //look for inter associations
            for (MAssociation association : multiModel.interAssociations()) {
                this.fAssociations.put(association.name(), association);
            }
            //look for inter invariants
            for (MClassInvariant invariant : multiModel.interConstraints()) {
                this.addClassInvariant(invariant);
            }
        } catch (Exception e) {
            // Must NOT silently swallow and continue: whichever fields hadn't
            // been copied yet when this fired are now permanently missing
            // from `this`, so returning it as if construction had succeeded
            // would hand every caller a silently-corrupted MMultiLevelModel
            // (see known-issues.org, Issue 1, for the associationclass-in-a-
            // model-block case this was originally found through). Rethrow
            // unchecked so this constructor's signature doesn't have to
            // change: ASTMultiLevelModel.gen() already wraps its call to
            // MultiLevelModelFactory.createMLM(...) in a try/catch(Exception)
            // that reports the message through the real error channel and
            // fails the compile cleanly, so this propagates there for free.
            throw new IllegalStateException(e.getMessage(), e);
        }
        fMediators = new HashMap<>();
    }



    @Override
    public void addModel(MModel model) throws Exception {
        super.addModel(model);

        // Association classes are not multi-level-aware: MultiModelFactory
        // .createAssociationClass() builds a plain MAssociationClassImpl
        // (unlike createClass()/createAssociation(), which build the
        // MInternalClass/AssociationImpl types every other class in a
        // multi-level model uses), so a `model ... end` block that declares
        // one hands us a class here that isn't an MInternalClassImpl. Reject
        // it with a clear message instead of letting the cast below throw a
        // bare ClassCastException -- see known-issues.org, Issue 1, and
        // examples/MLM-USE/WeightedEdgeClass.use for the supported
        // workaround (reify the edge as a plain class + two associations).
        // An association class declared directly in `inter-classes` is
        // unaffected: it never reaches this method at all (interClasses()
        // is copied via addClass(), not addModel(), in the constructor
        // above), and continues to work exactly as before.
        for (MClass cls : model.classes()) {
            if (!(cls instanceof MInternalClassImpl)) {
                throw new Exception("Association class `" + cls.name() + "' cannot be " +
                        "declared inside model `" + model.name() + "' of a multi-level model: " +
                        "association classes are not supported inside a `model' block there. " +
                        "Declare it in `inter-classes' instead, or reify the link as a plain " +
                        "class with two ordinary associations.");
            }
        }

        model.classes().forEach(cls -> ((MInternalClassImpl)cls).setMainModel(this));
        fModelsList.add(model);

        for (MClassInvariant inv : model.classInvariants()){
            inv.calculateExpandedExpression();
        }
    }

    /**
     * The model's actual parent level, per its own mediator -- not an
     * inference from where it happens to sit in {@link #fModelsList}.
     * That declaration-order list has no relationship to the hierarchy
     * at all (nothing requires a parent to be declared adjacent to, or
     * even near, its children); the only real source of truth for
     * "who is this model's parent" is the mediator {@code fMediators}
     * keys by this model's own name (see {@link #addMediator}). Returns
     * {@code null} both for an unknown model name and for a model with
     * no mediator, or a mediator with no parent (a root level).
     */
    public MModel getParentModel(String modelName) {
        if (!fModels.containsKey(modelName)){
            return null;
        }

        MMediator mediator = fMediators.get(modelName);
        if (mediator == null) {
            return null;
        }
        return mediator.getParentModel();
    }

    public void addMediator(MMediator mediator) throws Exception {
        if (fMediators.containsKey(mediator.name()))
            throw new Exception("MLM already contains a mediator `"
                    + mediator.name() + "'.");
        this.fMediators.put(mediator.name(), mediator);
    }
    public void removeMediator(String name){
        this.fMediators.remove(name);
    }

    public MMediator getMediator(String name){
        return fMediators.get(name);
    }

    public MClabject getClabject(String mediatorName, String clabjectName){
        MMediator mediator = this.getMediator(mediatorName);
        return mediator.getClabject(clabjectName);
    }

    public List<MClabject> clabjects() {
        List<MClabject> clabjects = new ArrayList<>();
        for (MMediator mediator : fMediators.values()){
            clabjects.addAll(mediator.clabjects());
        }
        return clabjects;
    }

    public List<MAssoclink> assoclinks() {
        List<MAssoclink> assoclinks = new ArrayList<>();
        for (MMediator mediator : fMediators.values()){
            assoclinks.addAll(mediator.assocLinks());
        }
        return assoclinks;
    }

    public List<MMediator> mediators(){
        return new ArrayList<>(fMediators.values());
    }

    /**
     * Every constituent model, ordered parent-before-child -- unlike
     * {@link #models()} (inherited unchanged from {@link MMultiModel},
     * a {@code TreeMap<String,MModel>}'s values, so alphabetical by
     * model name and unrelated to the hierarchy), this walks each
     * model's own mediator chain back to its root and sorts by that
     * depth. A model with no mediator (or whose mediator has no parent)
     * sorts as a root, depth 0.
     *
     * <p>Previously reimplemented independently, verbatim, in three
     * unrelated consumers ({@code PlantUmlDiagramGenerator},
     * {@code MMPrintVisitor}, {@code FlatUseTextRenderer}) because
     * {@code MMultiLevelModel} itself had no public method for this at
     * all -- moved here so there is exactly one implementation for all
     * three (and any future one) to share.
     */
    public List<MModel> levelsInHierarchyOrder() {
        Map<String, MModel> parentOf = new HashMap<>();
        for (MMediator mediator : mediators()) {
            if (mediator.getParentModel() != null) {
                parentOf.put(mediator.getCurrentModel().name(), mediator.getParentModel());
            }
        }

        Map<String, Integer> depth = new HashMap<>();
        for (MModel model : models()) {
            int d = 0;
            String curName = model.name();
            Set<String> seen = new HashSet<>();
            while (parentOf.containsKey(curName) && seen.add(curName)) {
                curName = parentOf.get(curName).name();
                d++;
            }
            depth.put(model.name(), d);
        }

        List<MModel> levels = new ArrayList<>(models());
        levels.sort(Comparator.comparingInt(m -> depth.getOrDefault(m.name(), 0)));
        return levels;
    }

    @Override
    public void addGeneralization(MGeneralization gen) throws MInvalidModelException {
        if (gen instanceof MClabject){
            //checks for conflicts
            MInternalClassImpl child = (MInternalClassImpl) gen.child();
            MInternalClassImpl parent = (MInternalClassImpl) gen.parent();
            List<MAttribute> childAttributes = child.allAttributes();
            List<MAttribute> parentAttributes = parent.allAttributes();
            for (MAttribute childAttr : childAttributes){
                for (MAttribute parentAttr : parentAttributes) {
                    if (childAttr.name().equals(parentAttr.name())){
                        // Same MAttribute instance reachable via two converging
                        // paths (e.g. a same-level subclass edge and this
                        // clabject's own instance-of edge both leading back to
                        // the same original declaration) is a harmless diamond,
                        // not a conflict -- allAttributes() hands back the
                        // declaring classifier's own attribute object by
                        // reference, so identity is exactly the right test.
                        if (childAttr == parentAttr) {
                            continue;
                        }
                        //conflict
                        if (((MClabject) gen).getRemovedAttribute(parentAttr.name()) != null){
                            //attribute is removed
                            continue;
                        }
                        else if (((MClabject) gen).getRenamedAttribute(parentAttr.name()) != null){
                            //attribute is renamed
                            continue;
                        }
                        //fGenGraph.removeEdge(gen);
                        throw new MInvalidModelException("Attribute "+childAttr.name()+" is present in both parent: " + gen.parent().name() + " and child: " + gen.child().name());
                    }
                }
            }
        }
        super.addGeneralization(gen);

    }

    public boolean checkState(){
        boolean result = true;
        // Iterate mediators and use each one's own, explicitly-recorded
        // parent model (mediator.getParentModel()) rather than pairing
        // models by walking this.models() in sequence -- that collection
        // is inherited from MMultiModel's TreeMap<String,MModel> and so
        // iterates *alphabetically* by model name, not in level-hierarchy
        // order, which silently paired each level with the wrong parent
        // whenever level names didn't happen to sort in hierarchy order.
        // This mirrors the already-correct checkWellDefinednessState().
        for (MMediator mediator : mediators()){
            MModel previousModel = mediator.getParentModel();
            if (previousModel == null) continue;
            UseSystemApi systemApi = new UseSystemApiUndoable(previousModel);

            //for each clabject, we create an object of the instance type
            for (MClabject clabject : mediator.clabjects()){
                try {
                    systemApi.createObject(clabject.parent().name(), clabject.child().name());

                }catch (Exception e){
                    System.out.println(e.getMessage());
                    return false;
                }
            }

            for (MAssoclink assoclink : mediator.assocLinks()){
                try {
                    String obj1 = ((MAssociation)assoclink.child()).associationEnds().get(0).cls().name();
                    String obj2 = ((MAssociation)assoclink.child()).associationEnds().get(1).cls().name();

                    systemApi.createLink(assoclink.parent().name(), obj1, obj2);

                }catch (Exception e){
                    System.out.println(e.getMessage());
                    return false;
                }
            }

            result = systemApi.checkState() && result;
        }

        return result;
    }

    public String checkWellDefinednessState() {
        return checkWellDefinednessState(NullPrintWriter.getInstance());
    }

    public String checkWellDefinednessState(PrintWriter error){
        Definedness result = Definedness.WellDefined;

        for (MMediator mediator : mediators()){
            MModel previousModel = mediator.getParentModel();
            if (previousModel == null) continue;
            UseMLMSystemApi systemApi = new UseMLMSystemApi(previousModel);

            //for each clabject, we create an object of the instance type
            for (MClabject clabject : mediator.clabjects()){
                try {
                    systemApi.createObject(clabject.parent().name(), clabject.child().name());

                }catch (Exception e){
                    error.println(e.getMessage());
                    return Definedness.NotWellDefined.toString();
                }
            }

            for (MAssoclink assoclink : mediator.assocLinks()){
                try {
                    String obj1 = ((MAssociation)assoclink.child()).associationEnds().get(0).cls().name();
                    String obj2 = ((MAssociation)assoclink.child()).associationEnds().get(1).cls().name();

                    systemApi.createLink(assoclink.parent().name(), obj1, obj2);

                }catch (Exception e){
                    error.println(e.getMessage());
                    return Definedness.NotWellDefined.toString();
                }
            }

            Satisfiability currRes = systemApi.checkWellDefinedness(error);
            if (currRes.equals(Satisfiability.NotSatisfied)){
                return Definedness.NotWellDefined.toString();
            }
            else if (currRes.equals(Satisfiability.PartiallySatisfied) && result.equals(Definedness.WellDefined)){
                result = Definedness.PartiallyDefined;
            }
        }
        if (result.equals(Definedness.PartiallyDefined)){
            return Definedness.WellDefined.toString();
        }
        else return result.toString();
    }

    @Override
    public void processWithVisitor(MMVisitor v) {
        v.visitMLM(this);
    }

    public List<MClass> powerTypes(){
        List<MClass> res = new ArrayList<>();
        for (MMediator mediator : this.mediators()){
            res.addAll(mediator.powerTypes());
        }
        return res;
    }

    public List<MClass> powerTypes(String levelName) {
        for (MMediator med : mediators()){
            if (med.parentModelName().equals(levelName)){
                return fMediators.get(med.name()).powerTypes();
            }
        }
        return new ArrayList<>();
    }

    // given a class and an invariant, calculates the subclasses that the invariant should be checked for
    // if the invariant is a local invariant (meaning it's defined within a model) then unless removed in a clabject it is applied to all subclasses
    // if the invariant is an inter-invariant then it's only applied to subclasses within the scope of the model of the base class
    public Set<MClass> subClassesOfClassForInvariant(MClass cls, MClassInvariant inv){
        Set<MClass> res = new HashSet<>();
        Set<MClass> children = ((MInternalClassImpl) cls).children();

        for (MClass child : children) {
            //check if the inheritance is of type clabject, if so the invariant might have been removed.
            if (!child.model().equals(cls.model())) {
                MGeneralization edge = cls.model().generalizationGraph()
                        .edgesBetween(child, cls).stream().findFirst().orElse(null);
                if (edge instanceof MClabject) {
                    MClabject clabject = (MClabject) edge;
                    if (clabject.getRemovedConstraints().contains(inv)){
                        continue;
                    }
                    if (this.interInvariants().contains(inv)){
                        continue;
                    }
                }
            }
            res.add(child);
            res.addAll(subClassesOfClassForInvariant(child, inv));
        }
        return res;
    }

    public Set<MClassInvariant> allClassInvariants(MClass cls) {
        //local constraints
        Set<MClassInvariant> res = cls.model().classInvariants(cls);
        //inter-constraints
        res.addAll(this.classInvariants(cls));

         for (MClass parent : cls.parents()){
             Set<MClassInvariant> parentConstraints = this.allClassInvariants(parent);
             res.addAll(parentConstraints);
             //check if the inheritance is of type clabject, if so the invariant might have been removed.
             if (!parent.model().equals(cls.model())) {
                 MGeneralization edge = cls.model().generalizationGraph()
                         .edgesBetween(cls, parent).stream().findFirst().orElse(null);
                 if (edge instanceof MClabject) {
                     MClabject clabject = (MClabject) edge;

                     for (MClassInvariant inv : parentConstraints){
                         if (clabject.getRemovedConstraints().contains(inv)){
                             res.remove(inv);
                         }
                         if (this.interInvariants().contains(inv)){
                             res.remove(inv);
                         }
                     }
                 }
             }
         }

        return res;
    }
}
