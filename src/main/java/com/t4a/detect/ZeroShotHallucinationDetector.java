package com.t4a.detect;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.FunctionDeclaration;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Tool;
import com.google.cloud.vertexai.generativeai.ChatSession;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.t4a.api.ActionType;
import com.t4a.api.DetectorAction;
import com.t4a.api.GuardRailException;
import com.t4a.api.JavaMethodExecutor;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * This class is Zero Shot method designed to assess the consistency of responses generated by a Large Language Model (LLM) and detect potential hallucinations. It operates by breaking down an original question into multiple granular questions, each probing different aspects or variations of the inquiry. These granular questions are then presented to the LLM separately, generating responses that are subsequently compared to the original question within its original context.
 *
 * During comparison, factors such as semantic coherence, relevance, and contextual alignment are evaluated to quantify the consistency between each response and the original question. This evaluation results in a percentage score for each response, representing its level of conformity with the original query.
 *
 * Finally, these individual percentage scores are aggregated to calculate a cumulative percentage. If the cumulative percentage surpasses a predefined threshold, it indicates a discrepancy or potential hallucination.
 *
 * By systematically analyzing responses in this manner, the class provides a robust mechanism for assessing the reliability and coherence of LLM-generated content
 */
@Log
@NoArgsConstructor
@AllArgsConstructor
public class ZeroShotHallucinationDetector implements DetectorAction {
    static String projectId = "cookgptserver";
    static String location = "us-central1";
    static String modelName = "gemini-1.0-pro";
    private int numberOfQuestions = 4;
    private String breakIntoQuestionPrompt = "Can you derive 4 questions from this context and provide me a single line without line breaks or backslash n character, you should reply with questions and nothing else - ";

    private static String sampleResponse = "Mohandas Karamchand Gandhi (ISO: Mōhanadāsa Karamacaṁda Gāṁdhī;[pron 1] 2 October 1869 – 30 January 1948) was an Indian lawyer, anti-colonial nationalist and political ethicist who employed nonviolent resistance to lead the successful campaign for India's independence from British rule. He inspired movements for civil rights and freedom across the world. The honorific Mahātmā (from Sanskrit 'great-souled, venerable'), first applied to him in South Africa in 1914, is now used throughout the world. Born and raised in a Hindu family in coastal Gujarat, Gandhi trained in the law at the Inner Temple in London, and was called to the bar in June 1891, at the age of 22. After two uncertain years in India, where he was unable to start a successful law practice, he moved to South Africa in 1893 to represent an Indian merchant in a lawsuit. He went on to live in South Africa for 21 years. There, Gandhi raised a family and first employed nonviolent resistance in a campaign for civil rights. In 1915, aged 45, he returned to India and soon set about organising peasants, farmers, and urban labourers to protest against discrimination and excessive land-tax.";
    @Override
    public ActionType getActionType() {
        return ActionType.HALLUCINATION;
    }

    @Override
    public String getDescription() {
        return "Detect Hallucination in response";
    }

    /**
     * This uses the Self Check method where the original request is broken down into multiple question
     * these questions are then sent to the Gemini for further answering, the original answer and new answer is mapped
     * to check the truth value
     * @param dd
     * @return
     * @throws GuardRailException
     */
    @Override
    public DetectValueRes execute(DetectValues dd) throws GuardRailException {
        DetectValueRes res = new DetectValueRes();
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            GenerateContentResponse response;


            GenerativeModel model =
                    GenerativeModel.newBuilder()
                            .setModelName(modelName)
                            .setVertexAi(vertexAI)
                            .build();
            ChatSession chatSession = new ChatSession(model);

            response = chatSession.sendMessage(breakIntoQuestionPrompt + dd.getResponse());

            String questions = ResponseHandler.getText(response);
            log.info(questions);
            JavaMethodExecutor methodAction = new JavaMethodExecutor();
            HallucinationAction questionAction = new HallucinationAction(projectId, location, modelName, sampleResponse);

            //FileWriteAction action = new FileWriteAction();
            FunctionDeclaration questionActionFun = methodAction.buildFunciton(questionAction);
            Tool tool = Tool.newBuilder()
                    .addFunctionDeclarations(questionActionFun)
                    .build();
            model =
                    GenerativeModel.newBuilder()
                            .setModelName(modelName)
                            .setVertexAi(vertexAI)
                            .setTools(Arrays.asList(tool))
                            .build();
            chatSession = new ChatSession(model);
            log.info(questions);
            response = chatSession.sendMessage("ask these questions -  " + questions + " - end of questions");
            log.info("" + ResponseHandler.getContent(response));
            List<HallucinationQA> hallucinationList = (List<HallucinationQA>) methodAction.action(response, questionAction);

            res.setHallucinationList(hallucinationList);

        } catch (IOException | InvocationTargetException | IllegalAccessException e) {
            //e.printStackTrace();
            throw new RuntimeException(e);
        }
        return res;
    }

    public static void main(String[] args) throws GuardRailException {
        DetectValues dv = new DetectValues();
        dv.setResponse(sampleResponse);
        ZeroShotHallucinationDetector detec = new ZeroShotHallucinationDetector();
        detec.execute(dv);

    }
}
