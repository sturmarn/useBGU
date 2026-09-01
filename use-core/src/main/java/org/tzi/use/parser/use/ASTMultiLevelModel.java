package org.tzi.use.parser.use;

import org.antlr.runtime.Token;
import org.tzi.use.analysis.coverage.*;
import org.tzi.use.parser.MLMContext;
import org.tzi.use.parser.MultiContext;
import org.tzi.use.uml.mm.*;

import java.util.*;
import java.util.stream.Collectors;

public class ASTMultiLevelModel extends ASTMultiModel{

    private ASTMultiModel fMultiModel;
    private final List<ASTMediator> fMediators;

    public ASTMultiLevelModel(Token name) {
        super(name);
        fMediators = new ArrayList<>();
    }

    public void addMultiModel(ASTMultiModel multiModel){
        this.fMultiModel = multiModel;
    }

    public void addMediator(ASTMediator mediator){
        this.fMediators.add(mediator);
    }

    public MMultiLevelModel gen(MLMContext mlmContext) {
        MMultiLevelModel mMultiLevelModel = null;
        try{
            MultiContext multiCtx = new MultiContext(mlmContext.filename(), mlmContext.getOut(), null, mlmContext.modelFactory());
            MMultiModel multiModel = fMultiModel.gen(multiCtx);
            // multiModel == null covers ASTMultiModel.gen()'s own per-model
            // loop returning early on error (e.g. a bad internal model, or
            // the association-class-in-a-model-block rejection in
            // MMultiLevelModel.addModel()) -- that loop's own check already
            // works, since each per-model sub-context forwards into multiCtx
            // via setMainContext.
            //
            // multiCtx.errorCount() > 0 (multiModel itself still non-null)
            // covers everything ASTMultiModel.gen() generates AFTER that
            // loop directly against this same multiCtx, with no further
            // check of its own: inter-enums, inter-classes (including
            // interAssociationClassDefinition), inter-associations, and
            // inter-prePost. Confirmed bug (2026-09-01, found while adding
            // CatMLM's own inter-associations support): a bad reference
            // there -- e.g. a bare class name where Model@Class was
            // required -- prints its error via multiCtx.reportError() but,
            // with nothing rechecking multiCtx.errorCount() afterward, the
            // overall compile still "succeeded", silently handing back a
            // model with that one broken piece just missing. Pre-existing
            // in plain MLM-USE too, just never exercised until an inter-*
            // section's own semantic-error path was actually tested.
            // (inter-constraints/interInvariant is unaffected -- it's
            // generated later via the separately-called genInterConstraints,
            // directly against mlmContext, which was already correct.)
            if (multiModel == null || multiCtx.errorCount() > 0){
                // NOTE: when multiModel is null because ASTMultiModel.gen()'s
                // own per-model loop already reported a specific cause (e.g.
                // inherited_roles_are_not_accessible_through_local_constraints
                // .fail, or the association-class-in-a-model-block rejection
                // in MMultiLevelModel.addModel()), this generic message is
                // reported *in addition* to that one, as a second line --
                // pre-existing, checked-in-test-expected behavior; don't
                // "fix" this into a single line without also updating every
                // .fail file in mlmParser/ that already expects both lines.
                throw new Exception("error parsing multi level model");
            }
            mMultiLevelModel = mlmContext.modelFactory().createMLM(fName.getText(), multiModel);
            mMultiLevelModel.setFilename(mlmContext.filename());
        }
        catch (Exception e){
            mlmContext.reportError(fName,e);
            return null;
        }


        Iterator<ASTMediator> medIt = fMediators.iterator();
        while(medIt.hasNext()) {
            ASTMediator mediator = medIt.next();

            MLMContext ctx = new MLMContext(mlmContext.filename(), mlmContext.getOut(), null, mlmContext.modelFactory());
            ctx.setMainContext(mlmContext);
            // Resolve the mediator's own declared "< ParentName" by name --
            // not by "whichever mediator happened to be processed just
            // before this one in fMediators' order". Mediators are free to
            // be declared/iterated in any order (nothing in the grammar
            // requires parent-before-child), so a positional/sequential
            // "previous model" stood in for the real parent link only by
            // accident, whenever a file happened to declare them in that
            // order; any other order produced a wrong (or, for the first
            // non-"NONE" mediator in a differently-ordered file, null)
            // parent model here.
            String parentModelName = mediator.getParentModelName();
            MModel parentModel = parentModelName.equals("NONE") ? null : mMultiLevelModel.getModel(parentModelName);
            ctx.setParentModel(parentModel);
            ctx.setModel(mMultiLevelModel);

            MModel currentModel = mMultiLevelModel.getModel(mediator.getName());
            if (currentModel == null){
                mlmContext.reportError(fName,"Model " + mediator.getName() + " not found");
                return null;
            }
            ctx.setCurrentModel(currentModel);

            try {
                MMediator mMediator = mediator.gen(ctx);
                mMultiLevelModel.addMediator(mMediator);
                // ctx forwards all its errors to mlmContext (see MultiContext.reportError),
                // so ctx's own counter never increments -- check the context errors actually
                // land on, matching the equivalent check in ASTMultiModel.gen().
                if (mlmContext.errorCount() > 0){
                    return null;
                }
            }
            catch(Exception e) {
                mlmContext.reportError(fName,e);
            }
        }

        mlmContext.setModel(mMultiLevelModel);
        fMultiModel.genInterConstraints(mlmContext);

        checkInvariantParsingWarning(mMultiLevelModel, mlmContext);

        return mMultiLevelModel;
    }

    public void checkInvariantParsingWarning(MMultiLevelModel mMultiLevelModel, MLMContext mlmContext) {
        Map<MModelElement, CoverageData> completeData = CoverageAnalyzer
                .calculateInvariantCoverage(mMultiLevelModel, true);

        for (MMediator med : mMultiLevelModel.mediators()) {
            for (MClabject clab : med.clabjects()) {
                for (MAttribute attr : clab.getRemovedAttributes()) {
                    for (MClassInvariant inv : mMultiLevelModel.classInvariants()) {
                        if(clab.child().isSubClassifierOf(inv.cls()) && completeData.get(inv).getAttributeCoverage().containsKey(attr)) {
                            if (clab.getRemovedConstraints().contains(inv)) {
                                continue;
                            }

                            // if the attribute source class in the invariant isn't the same as the context class, then it shouldn't throw an error.
                            if(!completeData.get(inv).getAttributeAccessCoverage().keySet().stream().anyMatch(aa -> aa.getAttribute().equals(attr) && aa.getSourceClass().equals(inv.cls()))) continue;

                            // if the attribute is removed from the clabject, and is accessed by an invariant (local), but the class can be navigated to it, then it shouldn't throw an error.
                            boolean isOtherEndRemoved = false;
                            for(MAssociation assoc : completeData.get(inv).getAssociationCoverage().keySet()) {
                                for (MAssociationEnd e : assoc.associationEnds()) {
                                    if (!clab.child().isSubClassifierOf(e.cls())) {
                                        if (clab.getRemovedRoles().contains(e)) {
                                            isOtherEndRemoved = true;
                                        }
                                        break;
                                    }
                                }
                            }

                            if(isOtherEndRemoved) continue;

                            // if the attribute is removed from the clabject, but the attribute is inherited from other source (superclass etc.), then it shouldn't throw an error.
                            boolean isAttributeExists = false;
                            for(MAttribute currentAttr : clab.child().allAttributes()) {
                                if(currentAttr.name().equals(attr.name())) {
                                    isAttributeExists = true;
                                    break;
                                }
                            }

                            if(isAttributeExists) continue;

                            mlmContext.reportError(fName,
                                    "Attribute " + attr.name()
                                    + "\n\tthat is accessed by invariant " + inv.name()
                                    + "\n\tin " + clab.name() + " not inherited");
                        }
                    }
                }

                for (MAttributeRenaming attributeRenaming : clab.getAttributeRenaming()) {
                    MAttribute attr = attributeRenaming.attribute();
                    for(MAttribute currentAttr : clab.child().allAttributes()) {
                        if(currentAttr.name().equals(attr.name()))
                            mlmContext.reportWarning(fName,
                                    "Attribute " + attr.name()
                                    + "\n\tis renamed to  " + attributeRenaming.newName() + ", "
                                    + "\n\tand the base attribute " + attr.name() + " is also inherited.");
                    }
                    for (MClassInvariant inv : mMultiLevelModel.classInvariants()) {
                        if (completeData.get(inv).getAttributeCoverage().keySet().contains(attr)) {
                            // 1. if a user renames an attribute, and remove the invariant (local), then it shouldn't throw an error
                            // 2. if a user renames an attribute, and an inter-constraint related to it, then it shouldn't throw an error
                            if(clab.getRemovedConstraints().contains(inv) || mMultiLevelModel.interInvariants().contains(inv) )
                                continue;
                            mlmContext.reportError(fName,
                                    "Attribute " + attr.name()
                                    + "\n\tis renamed in " + clab.name()
                                    + "\n\tbut is accessed by invariant " + inv.name());
                        }
                    }
                }

                for (MAssociationEnd end : clab.getOnlyClabjectRemovedRoles()) {
                    for (MClassInvariant inv : mMultiLevelModel.classInvariants()) {
                        if (completeData.get(inv).getPropertyCoverage().keySet().contains(end)) {
                            if (clab.getRemovedConstraints().contains(inv)) {
                                continue;
                            }
                            // if its an inter-constraints, and the context class isn't the same as the clabject class, then it shouldn't throw an error.
                            if(mMultiLevelModel.interInvariants().contains(inv) && !inv.cls().equals(clab.child())) continue;

                            if (!clab.parent().isSubClassifierOf(inv.cls())){
                                continue;
                            }

                            // if the role is removed from the clabject, and is accessed by an invariant (local), it should throw an error.
                            mlmContext.reportError(fName,
                                    "Role " + end.name()
                                    + "\n\tis removed by " + clab.name()
                                    + "\n\tbut is accessed by invariant " + inv.name());
                        }
                    }
                }

                for(MAssociationEnd end : clab.getOnlyAssoclinkRemovedRoles()) {
                    for (MClassInvariant inv : mMultiLevelModel.classInvariants()) {
                        if (completeData.get(inv).getPropertyCoverage().keySet().contains(end)) {
                            if (clab.getRemovedConstraints().contains(inv)) {
                                continue;
                            }

                            // if the role is removed from the clabject, and is accessed by an invariant (local), but the class cant be navigated to it, then it shouldn't throw an error.
                            if(clab.getRemovedRoles().contains(end)) {
                                continue;
                            }

                            MAssoclink assoclink = med.assoclinkOfClabject(clab.name());
                            mlmContext.reportError(fName,
                                    "Role " + end.name()
                                            + "\n\tremoved by assoclink: " + assoclink
                                            + "\n\tand by: " + clab
                                            + "\n\tbut accessed by invariant " + inv.name());
                        }
                    }
                }

            }
        }
    }
}
