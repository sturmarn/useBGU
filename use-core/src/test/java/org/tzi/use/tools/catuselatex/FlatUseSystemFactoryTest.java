package org.tzi.use.tools.catuselatex;

import junit.framework.TestCase;
import org.tzi.use.gen.tool.GGeneratorArguments;
import org.tzi.use.parser.use.USECompilerMLM;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MMultiLevelModel;
import org.tzi.use.uml.mm.MultiLevelModelFactory;
import org.tzi.use.uml.sys.MSystem;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Proves the actual point of {@link FlatUseSystemFactory}: the ASSL
 * snapshot generator ({@code GGenerator}, via {@code MSystem.generator()})
 * -- architecturally blind to multi-level constructs, see the factory's
 * own class doc comment -- genuinely runs an ASSL procedure end-to-end
 * against a real multi-level model, once that model has passed through
 * this factory first. Nothing in this codebase exercised
 * {@code startProcedure} against any model, multi-level or otherwise,
 * before this test.
 */
public class FlatUseSystemFactoryTest extends TestCase {

    private static final String BOUNDED_DEGREE_MLM =
            "MLM BoundedDegree\n" +
            "\n" +
            "model M2\n" +
            "\n" +
            "class Node\n" +
            "end\n" +
            "\n" +
            "association LinksTo between\n" +
            "  Node[0..3] role targets\n" +
            "  Node[*] role sources\n" +
            "end\n" +
            "\n" +
            "constraints\n" +
            "context Node inv NoSelfLink:\n" +
            "  self.targets->excludes(self)\n" +
            "\n" +
            "\n" +
            "model M1\n" +
            "\n" +
            "class SpecialNode\n" +
            "attributes\n" +
            "  tag: String\n" +
            "end\n" +
            "\n" +
            "\n" +
            "inter-constraints\n" +
            "context M1@SpecialNode inv FewSaturatedSpecials:\n" +
            "  M1@SpecialNode.allInstances()->select(n | n.targets->size() = 3)->size() <= 2\n" +
            "\n" +
            "\n" +
            "mediator M2 < NONE\n" +
            "end\n" +
            "\n" +
            "mediator M1 < M2\n" +
            "clabject SpecialNode : Node\n" +
            "end\n" +
            "end\n";

    private MMultiLevelModel compileMlm(String src) {
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf);
        MMultiLevelModel mlm = USECompilerMLM.compileMLMSpecification(
                new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8)),
                "flatten-gen-test.use", err, new MultiLevelModelFactory());
        err.flush();
        assertNotNull("MLM-USE compilation failed:\n" + errBuf, mlm);
        return mlm;
    }

    /** The classpath resource's real filesystem path -- startProcedure reads the ASSL file itself, off disk. */
    private String asslFixturePath() throws URISyntaxException {
        URL url = getClass().getResource("FlattenedGeneration.assl");
        assertNotNull("test fixture FlattenedGeneration.assl missing from test resources", url);
        return new File(url.toURI()).getAbsolutePath();
    }

    public void testGeneratorRunsAgainstFlattenedMultiLevelModel() throws Exception {
        MMultiLevelModel mlm = compileMlm(BOUNDED_DEGREE_MLM);

        StringWriter flattenErrBuf = new StringWriter();
        PrintWriter flattenErr = new PrintWriter(flattenErrBuf);
        MSystem system = FlatUseSystemFactory.createFlattenedSystem(mlm, "BoundedDegree", flattenErr);
        flattenErr.flush();
        assertNotNull("flattening/recompiling the multi-level model failed:\n" + flattenErrBuf, system);

        GGeneratorArguments args = GGeneratorArguments.parseCallstring(
                asslFixturePath() + " makeLinkedNodes()");
        assertNotNull("failed to parse the ASSL call string", args);

        system.generator().startProcedure(args.getCallString(), args);
        assertTrue("generation against the flattened model produced no result at all", system.generator().hasResult());
        system.generator().acceptResult();

        MClass node = system.model().getClass("Node");
        assertNotNull("flattened model lost the Node class entirely", node);
        assertEquals("makeLinkedNodes() should have created exactly 2 Node objects",
                2, system.state().objectsOfClass(node).size());
    }
}
