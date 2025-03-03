package org.itech.ahb.lib.astm.interpretation;

import java.util.List;
import org.itech.ahb.lib.astm.concept.ASTMFrame;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.ASTMRecord;

/**
 * This class provides a default implementation of the ASTMInterpreterFactory interface.
 */
public class DefaultASTMInterpreterFactory implements ASTMInterpreterFactory {

  @Override
  public ASTMInterpreter createInterpreterForFrames(List<ASTMFrame> frames) {
    return new DefaultASTMInterpreter();
  }

  @Override
  public ASTMInterpreter createInterpreterForRecords(List<ASTMRecord> records) {
    return new DefaultASTMInterpreter();
  }

  @Override
  public ASTMInterpreter createInterpreter(ASTMMessage message) {
    return new DefaultASTMInterpreter();
  }

  @Override
  public ASTMInterpreter createInterpreterForText(String text) {
    return new DefaultASTMInterpreter();
  }
}
