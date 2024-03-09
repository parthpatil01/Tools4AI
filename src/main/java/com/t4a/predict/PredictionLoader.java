package com.t4a.predict;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.ChatSession;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.t4a.action.ExtendedPredictedAction;
import com.t4a.action.http.HttpPredictedAction;
import com.t4a.action.shell.ShellPredictedAction;
import com.t4a.api.AIAction;
import com.t4a.api.ActionType;
import lombok.Getter;
import lombok.extern.java.Log;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
/**
 * The {@code PredictionLoader} class is responsible for managing the prediction process
 * by interacting with various prediction models and loading actions based on predictions.
 * <p>
 * This class dynamically loads actions from clases and provides methods for
 * predicting actions, explaining actions, and building prompts for interaction with users.
 * </p>
 */
@Log
public class PredictionLoader {

    @Getter
    private Map<String,AIAction> predictions = new HashMap<String,AIAction>();
    private StringBuffer actionNameList = new StringBuffer();
    private static PredictionLoader predictionLoader =null;

    private final String PREACTIONCMD = "here is my prompt - ";
    private final  String ACTIONCMD = "- what action do you think we should take ";

    private final  String POSTACTIONCMD = " - reply back with ";
    private final  String NUMACTION = " action only";

    private final  String NUMACTION_MULTIPROMPT = " actions only, in comma seperated list without any additional special characters";
    private ChatSession chat;
    private ChatSession chatExplain;
    private String projectId;
    private String location;
    private String modelName;
    private PredictionLoader() {
        initProp();
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {


            GenerativeModel model =
                    GenerativeModel.newBuilder()
                            .setModelName(modelName)
                            .setVertexAi(vertexAI)
                            .build();
            GenerativeModel modelExplain =
                    GenerativeModel.newBuilder()
                            .setModelName(modelName)
                            .setVertexAi(vertexAI)
                            .build();
            chat = model.startChat();
            chatExplain = modelExplain.startChat();
        }

    }

    public String getProjectId() {
        return projectId;
    }

    public String getModelName() {
        return modelName;
    }

    public String getLocation() {
        return location;
    }

    public void initProp() {
        try (InputStream inputStream = PredictionLoader.class.getClassLoader().getResourceAsStream("tools4ai.properties")) {
            Properties prop = new Properties();
            prop.load(inputStream);

            // Read properties
            projectId = prop.getProperty("projectId").trim();
            location = prop.getProperty("location").trim();
            modelName = prop.getProperty("modelName").trim();

            // Use the properties
            log.info("projectId: " + projectId);
            log.info("location: " + location);
            log.info("modelName: " + modelName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<AIAction> getPredictedAction(String prompt, int num)  {
        GenerateContentResponse response = null;
        List<AIAction> actionList = new ArrayList<AIAction>();
        try {
            response = chat.sendMessage(buildPrompt(prompt,num));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String actionNameListStr = ResponseHandler.getText(response);
        String[] actionNames = actionNameListStr.split(",");
        if(!(actionNames.length >1))
            actionNames = actionNameListStr.split("\n");
        // Iterate over each action name
        for (String actionName : actionNames) {
            // Trim the action name to remove leading/trailing spaces
            actionName = actionName.trim();

            // Get the action and add it to the action list
            AIAction aiAction = getAiAction(actionName);
            actionList.add(aiAction);
        }
        return actionList;

    }


    public AIAction getPredictedAction(String prompt)  {
        GenerateContentResponse response = null;
        try {
            response = chat.sendMessage(buildPrompt(prompt,1));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String actionName = ResponseHandler.getText(response);
        return getAiAction(actionName);

    }

    public String getPredictedActionMultiStep(String prompt)  {
        GenerateContentResponse response = null;
        try {
            response = chat.sendMessage(buildPromptMultiStep(prompt));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String actionName = ResponseHandler.getText(response);
        log.info(actionName);
        return actionName;

    }

    public String explainAction(String prompt, String action)  {
        GenerateContentResponse response = null;
        try {
            response = chatExplain.sendMessage("explain why this action "+action+" is appropriate for this command "+prompt+" out of all these actions "+actionNameList);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return  ResponseHandler.getText(response);


    }

    private AIAction getAiAction(String actionName) {
        log.info(" Trying to load "+actionName);
        AIAction action = predictions.get(actionName);


            if(action.getActionType() == ActionType.SHELL) {
                ShellPredictedAction shellAction = (ShellPredictedAction)action;

                return shellAction;
            } else if (action.getActionType() == ActionType.HTTP) {
               if(action instanceof HttpPredictedAction) {
                   HttpPredictedAction genericHttpAction = (HttpPredictedAction)action;

                   return genericHttpAction;

               }  if(action.getActionType() == ActionType.EXTEND) {
                    ExtendedPredictedAction shellAction = (ExtendedPredictedAction)action;

                    return shellAction;
                }
            }
            return action;

    }

    public static PredictionLoader getInstance() {
        if(predictionLoader == null) {
            predictionLoader = new PredictionLoader();
            predictionLoader.processCP();
            predictionLoader.loadShellCommands();
            predictionLoader.loadHttpCommands();
            predictionLoader.loadSwaggerHttpActions();

        }
        return predictionLoader;
    }





    private void loadShellCommands()  {
        ShellPredictionLoader shellLoader = new ShellPredictionLoader();
        try {
            shellLoader.load(predictions,actionNameList);
        } catch (LoaderException e) {
            log.warning(e.getMessage());
        }
    }
    private void loadSwaggerHttpActions() {
        SwaggerPredictionLoader httpLoader = new SwaggerPredictionLoader();
        try {
            httpLoader.load(predictions,actionNameList);
        } catch (LoaderException e) {
            log.warning(e.getMessage());
        }
    }
    private void loadHttpCommands()  {
        HttpRestPredictionLoader httpLoader = new HttpRestPredictionLoader();
        try {
            httpLoader.load(predictions,actionNameList);
        } catch (LoaderException e) {
            log.warning(e.getMessage());
        }
    }

    public void processCP() {


        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(true);
        provider.addIncludeFilter(new AnnotationTypeFilter(Predict.class));
        provider.addIncludeFilter(new AnnotationTypeFilter(ActivateLoader.class));
        Set<BeanDefinition> beanDefs = provider
                .findCandidateComponents("*");
        beanDefs.stream().forEach(beanDefinition -> {
            try {
                Class actionCLAZZ = Class.forName(beanDefinition.getBeanClassName());
                if (AIAction.class.isAssignableFrom(actionCLAZZ)) {
                    log.info("Class " + actionCLAZZ + " implements AIAction");
                    if (ExtendedPredictedAction.class.isAssignableFrom(actionCLAZZ))
                        log.warning("You cannot predict extended option implement AIAction instead"+actionCLAZZ);
                    else
                        addAction(actionCLAZZ);
                } else if (ExtendedPredictionLoader.class.isAssignableFrom(actionCLAZZ)) {
                    log.info("Class " + actionCLAZZ + " implements Loader");
                    loadFromLoader(actionCLAZZ);
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }


    private  void loadFromLoader(Class clazz) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        ExtendedPredictionLoader instance = (ExtendedPredictionLoader)clazz.getDeclaredConstructor().newInstance();
        try {
            Map<String, ExtendedPredictedAction> extendedPredictOptionsMap = instance.getExtendedActions();
            for (String key : extendedPredictOptionsMap.keySet()) {
                log.info(" names "+actionNameList);
                actionNameList.append(key).append(",");
                predictions.put(key,extendedPredictOptionsMap.get(key));
            }

        } catch (LoaderException e) {
            log.severe(e.getMessage()+" for "+clazz.getName());

        }
    }



    private  void addAction(Class clazz) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        AIAction instance = (AIAction)clazz.getDeclaredConstructor().newInstance();
        String actionName = instance.getActionName();

        actionNameList.append(actionName+",");
        predictions.put(actionName,instance);
    }

    public Map<String, AIAction> getPredictions() {
        return predictions;
    }

    public StringBuffer getActionNameList() {
        return actionNameList;
    }

    private String buildPrompt(String prompt, int number) {
        String append = NUMACTION;
        if(number > 1)
            append = NUMACTION_MULTIPROMPT;
        String query = PREACTIONCMD+prompt+ACTIONCMD+actionNameList.toString()+POSTACTIONCMD+number +append;
        log.info(query);
        return query;
    }

    private String buildPromptMultiStep(String prompt) {
        return "break down this prompt into multiple prompts and associated action in comma separated list , this is your prompt - "+prompt+" - action list is here -"+actionNameList+" you will provide the result in this format - sub-prompt,action. If not action matches the sub-prompt please put blankAction";
    }

}
