package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("HL7ResultParser control recognition")
class HL7ResultParserControlRecognitionTest {

  private static List<String> segments(String... segments) {
    return List.of(segments);
  }

  @Nested
  @DisplayName("Profile rules")
  class ProfileRules {

    @Test
    void emptyPatientIdentifierComponentIsMissingEvidence() {
      ParsedResults parsed = HL7ResultParser.parse(
        segments("PID|1||^AUTHORITY", "OBX|1|NM|WBC||7.5|10*3/uL"),
        TestControlRecognitions.rule("SPECIMEN_ID_PATTERN", null, "^$")
      );

      assertNotNull(parsed);
      assertEquals("HL7-UNKNOWN", parsed.accessionNumber());
      assertFalse(parsed.results().get(0).isControl());
      assertEquals("", parsed.results().get(0).controlRecognitionAssessment().evaluations().get(0).rawValue());
    }

    @ParameterizedTest
    @CsvSource({ "SPECIMEN_ID_PREFIX,HL7-", "SPECIMEN_ID_PATTERN,^HL7-UNKNOWN$" })
    void displayFallbackIsNotSpecimenRecognitionEvidence(String ruleType, String operand) {
      ParsedResults parsed = HL7ResultParser.parse(
        segments("MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1", "OBX|1|NM|WBC||7.5|10*3/uL"),
        TestControlRecognitions.rule(ruleType, null, operand)
      );

      assertNotNull(parsed);
      assertEquals("HL7-UNKNOWN", parsed.accessionNumber());
      var result = parsed.results().get(0);
      assertFalse(result.isControl(), "a display placeholder must not classify a result as a control");
      var evidence = result.controlRecognitionAssessment().evaluations().get(0);
      assertEquals("specimenId", evidence.sourceField());
      assertEquals("", evidence.rawValue());
      assertFalse(evidence.matched());
    }

    @Test
    void missingAccessionStillAllowsRecognitionFromAnActualProfileSelectedField() {
      ParsedResults parsed = HL7ResultParser.parse(
        segments("OBR|1|||QC-PANEL", "OBX|1|NM|WBC||7.5|10*3/uL"),
        TestControlRecognitions.rule("FIELD_EQUALS", "OBR.4", "QC-PANEL")
      );

      assertNotNull(parsed);
      assertEquals("HL7-UNKNOWN", parsed.accessionNumber());
      assertTrue(parsed.results().get(0).isControl());
      var evidence = parsed.results().get(0).controlRecognitionAssessment().evaluations().get(0);
      assertEquals("OBR.4", evidence.sourceField());
      assertEquals("QC-PANEL", evidence.rawValue());
    }

    @Test
    void obrFieldRuleFlagsAllObservationsAsControl() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1||ACC001|QC-PANEL",
        "OBX|1|NM|WBC||7.5|10*3/uL",
        "OBX|2|NM|RBC||4.82|10*6/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_EQUALS", "OBR.4", "QC-PANEL");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertEquals(2, parsed.results().size());
      assertTrue(parsed.results().get(0).isControl());
      assertTrue(parsed.results().get(1).isControl());
    }

    @Test
    void pidFieldRuleFlagsAsControl() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||QC-PATIENT",
        "OBR|1||ACC001|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_EQUALS", "PID.3", "QC-PATIENT");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertTrue(parsed.results().get(0).isControl());
    }

    @Test
    void specimenPrefixRuleUsesTheAccession() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1||QC-LOT-001|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("SPECIMEN_ID_PREFIX", null, "QC-");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertEquals("QC-LOT-001", parsed.accessionNumber());
      assertTrue(parsed.results().get(0).isControl());
    }

    @Test
    void fieldContainsRuleUsesTheSelectedObrField() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1||ACC001|CTRL-CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_CONTAINS", "OBR.4", "CTRL");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertTrue(parsed.results().get(0).isControl());
    }

    @Test
    void specimenPatternRuleMatchesTheAccession() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1||QC-LOT-001|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule(
        "SPECIMEN_ID_PATTERN",
        null,
        "^QC-LOT-\\d{3}$"
      );

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertTrue(parsed.results().get(0).isControl());
    }
  }

  @Nested
  @DisplayName("Explicit NONE")
  class ExplicitNone {

    @Test
    void noneDoesNotClassifyControlLookingValues() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||QC-PATIENT",
        "OBR|1||QC-LOT-001|QC-PANEL",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );

      ParsedResults parsed = HL7ResultParser.parse(message, ControlResultRecognition.none());

      assertNotNull(parsed);
      assertFalse(parsed.results().get(0).isControl());
    }

    @Test
    void nonMatchingRuleDoesNotClassify() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1||ACC001|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_EQUALS", "OBR.4", "QC-PANEL");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertFalse(parsed.results().get(0).isControl());
    }
  }

  @Nested
  @DisplayName("Field extraction")
  class FieldExtraction {

    @Test
    void obrFieldsUseHl7Indexes() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1||PAT001",
        "OBR|1|PLACER|FILLER|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_EQUALS", "OBR.1", "1");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertTrue(parsed.results().get(0).isControl());
    }

    @Test
    void pidFieldsUseHl7Indexes() {
      List<String> message = segments(
        "MSH|^~\\&|A|B|C|D|20260326||ORU^R01|1|P|2.3.1",
        "PID|1|EXT|INT|ALT|DOE^JOHN",
        "OBR|1||ACC001|CBC",
        "OBX|1|NM|WBC||7.5|10*3/uL"
      );
      ControlResultRecognition recognition = TestControlRecognitions.rule("FIELD_CONTAINS", "PID.5", "DOE");

      ParsedResults parsed = HL7ResultParser.parse(message, recognition);

      assertNotNull(parsed);
      assertTrue(parsed.results().get(0).isControl());
    }
  }
}
