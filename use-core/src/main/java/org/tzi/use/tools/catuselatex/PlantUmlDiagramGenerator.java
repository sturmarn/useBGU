package org.tzi.use.tools.catuselatex;

import org.tzi.use.graph.DirectedGraph;
import org.tzi.use.uml.mm.*;

import java.util.*;

/**
 * Renders a compiled {@link MMultiLevelModel} as PlantUML class-diagram
 * source: one package per level, classes stereotyped {@code <<category>>}
 * (a class that is some clabject's powerclass) or {@code <<clabject>>} (a
 * class that instantiates one), same-level generalizations as solid
 * inheritance arrows, and clabject links as dashed {@code <<instanceOf>>}
 * arrows -- pulled from the same combined {@link MModel#generalizationGraph()}
 * used by {@code MInternalClassImpl} to resolve inherited attributes/roles.
 */
public class PlantUmlDiagramGenerator {

    public static String generate(MMultiLevelModel mlm) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("hide circle\n");
        sb.append("skinparam classAttributeIconSize 0\n");
        sb.append("skinparam monochrome true\n");
        sb.append("skinparam class {\n");
        sb.append("  BackgroundColor<<category>> White\n");
        sb.append("  BackgroundColor<<clabject>> White\n");
        sb.append("}\n\n");

        Set<String> powerclassNames = new HashSet<>();
        Set<String> clabjectChildNames = new HashSet<>();
        for (MClabject clabject : mlm.clabjects()) {
            powerclassNames.add(clabject.parent().name());
            clabjectChildNames.add(clabject.child().name());
        }

        for (MModel level : mlm.levelsInHierarchyOrder()) {
            sb.append("package \"").append(level.name()).append("\" {\n");
            List<MClass> classes = new ArrayList<>(level.classes());
            classes.sort(Comparator.comparing(MClass::name));
            for (MClass cls : classes) {
                appendClass(sb, cls, powerclassNames, clabjectChildNames);
            }
            sb.append("}\n\n");
        }

        DirectedGraph<MClassifier, MGeneralization> genGraph = mlm.generalizationGraph();
        Iterator<MGeneralization> edges = genGraph.edgeIterator();
        List<String> relations = new ArrayList<>();
        while (edges.hasNext()) {
            MGeneralization edge = edges.next();
            String child = alias(edge.child().name());
            String parent = alias(edge.parent().name());
            if (edge instanceof MClabject) {
                relations.add(child + " ..> " + parent + " : <<instanceOf>>");
            } else {
                relations.add(child + " --|> " + parent);
            }
        }
        Collections.sort(relations);
        for (String rel : relations) {
            sb.append(rel).append("\n");
        }
        sb.append("\n");

        for (MModel level : mlm.models()) {
            List<MAssociation> assocs = new ArrayList<>(level.associations());
            assocs.sort(Comparator.comparing(MAssociation::name));
            for (MAssociation assoc : assocs) {
                appendAssociation(sb, assoc);
            }
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    private static void appendClass(StringBuilder sb, MClass cls,
                                     Set<String> powerclassNames, Set<String> clabjectChildNames) {
        String shortName = shortName(cls.name());
        String stereotype = clabjectChildNames.contains(cls.name()) ? "clabject"
                : powerclassNames.contains(cls.name()) ? "category" : null;

        sb.append("  class \"").append(shortName).append("\" as ").append(alias(cls.name()));
        if (stereotype != null) {
            sb.append(" <<").append(stereotype).append(">>");
        }
        List<MAttribute> attrs = new ArrayList<>(cls.attributes());
        if (attrs.isEmpty()) {
            sb.append("\n");
            return;
        }
        attrs.sort(Comparator.comparing(MAttribute::name));
        sb.append(" {\n");
        for (MAttribute attr : attrs) {
            sb.append("    +").append(attr.name()).append(" : ").append(shortTypeName(attr.type().toString())).append("\n");
        }
        sb.append("  }\n");
    }

    private static void appendAssociation(StringBuilder sb, MAssociation assoc) {
        List<MAssociationEnd> ends = assoc.associationEnds();
        if (ends.size() == 2) {
            MAssociationEnd a = ends.get(0);
            MAssociationEnd b = ends.get(1);
            sb.append(alias(a.cls().name()))
                    .append(" \"").append(endLabel(a)).append("\" -- \"").append(endLabel(b)).append("\" ")
                    .append(alias(b.cls().name()))
                    .append(" : ").append(shortName(assoc.name())).append("\n");
        } else {
            String diamondAlias = "assoc_" + alias(assoc.name());
            sb.append("diamond \"").append(shortName(assoc.name())).append("\" as ").append(diamondAlias).append("\n");
            for (MAssociationEnd end : ends) {
                sb.append(alias(end.cls().name())).append(" -- \"").append(endLabel(end)).append("\" ")
                        .append(diamondAlias).append("\n");
            }
        }
    }

    private static String endLabel(MAssociationEnd end) {
        return end.name() + "\\n" + end.multiplicity();
    }

    private static String shortName(String qualifiedName) {
        int at = qualifiedName.indexOf('@');
        return at < 0 ? qualifiedName : qualifiedName.substring(at + 1);
    }

    private static String shortTypeName(String typeText) {
        int at = typeText.indexOf('@');
        return at < 0 ? typeText : typeText.substring(at + 1);
    }

    private static String alias(String qualifiedName) {
        return qualifiedName.replace("@", "_");
    }
}
