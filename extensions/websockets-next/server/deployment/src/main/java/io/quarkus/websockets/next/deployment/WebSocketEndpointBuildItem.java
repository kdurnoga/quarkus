package io.quarkus.websockets.next.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.Type;
import org.jboss.jandex.Type.Kind;

import io.quarkus.arc.deployment.TransformedAnnotationsBuildItem;
import io.quarkus.arc.processor.Annotations;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.quarkus.websockets.next.WebSocketServerException;
import io.quarkus.websockets.next.deployment.CallbackArgument.InvocationBytecodeContext;
import io.quarkus.websockets.next.deployment.CallbackArgument.ParameterContext;
import io.quarkus.websockets.next.runtime.WebSocketEndpoint.ExecutionModel;
import io.quarkus.websockets.next.runtime.WebSocketEndpointBase;

/**
 * This build item represents a WebSocket endpoint class.
 */
public final class WebSocketEndpointBuildItem extends MultiBuildItem {

    public final BeanInfo bean;
    public final String path;
    public final WebSocket.ExecutionMode executionMode;
    public final Callback onOpen;
    public final Callback onTextMessage;
    public final Callback onBinaryMessage;
    public final Callback onPongMessage;
    public final Callback onClose;

    public WebSocketEndpointBuildItem(BeanInfo bean, String path, WebSocket.ExecutionMode executionMode, Callback onOpen,
            Callback onTextMessage, Callback onBinaryMessage, Callback onPongMessage, Callback onClose) {
        this.bean = bean;
        this.path = path;
        this.executionMode = executionMode;
        this.onOpen = onOpen;
        this.onTextMessage = onTextMessage;
        this.onBinaryMessage = onBinaryMessage;
        this.onPongMessage = onPongMessage;
        this.onClose = onClose;
    }

    public static class Callback {

        public final AnnotationInstance annotation;
        public final MethodInfo method;
        public final ExecutionModel executionModel;
        public final MessageType messageType;
        public final List<CallbackArgument> arguments;

        public Callback(AnnotationInstance annotation, MethodInfo method, ExecutionModel executionModel,
                CallbackArgumentsBuildItem callbackArguments, TransformedAnnotationsBuildItem transformedAnnotations,
                String endpointPath) {
            this.method = method;
            this.annotation = annotation;
            this.executionModel = executionModel;
            if (WebSocketDotNames.ON_BINARY_MESSAGE.equals(annotation.name())) {
                this.messageType = MessageType.BINARY;
            } else if (WebSocketDotNames.ON_TEXT_MESSAGE.equals(annotation.name())) {
                this.messageType = MessageType.TEXT;
            } else if (WebSocketDotNames.ON_PONG_MESSAGE.equals(annotation.name())) {
                this.messageType = MessageType.PONG;
            } else {
                this.messageType = MessageType.NONE;
            }
            this.arguments = collectArguments(annotation, method, callbackArguments, transformedAnnotations, endpointPath);
        }

        public boolean isOnOpen() {
            return annotation.name().equals(WebSocketDotNames.ON_OPEN);
        }

        public boolean isOnClose() {
            return annotation.name().equals(WebSocketDotNames.ON_CLOSE);
        }

        public Type returnType() {
            return method.returnType();
        }

        public Type messageParamType() {
            return acceptsMessage() ? method.parameterType(0) : null;
        }

        public boolean isReturnTypeVoid() {
            return returnType().kind() == Kind.VOID;
        }

        public boolean isReturnTypeUni() {
            return WebSocketDotNames.UNI.equals(returnType().name());
        }

        public boolean isReturnTypeMulti() {
            return WebSocketDotNames.MULTI.equals(returnType().name());
        }

        public boolean acceptsMessage() {
            return messageType != MessageType.NONE;
        }

        public boolean acceptsBinaryMessage() {
            return messageType == MessageType.BINARY || messageType == MessageType.PONG;
        }

        public boolean acceptsMulti() {
            return acceptsMessage() && method.parameterType(0).name().equals(WebSocketDotNames.MULTI);
        }

        public MessageType messageType() {
            return messageType;
        }

        public boolean broadcast() {
            AnnotationValue broadcastValue = annotation.value("broadcast");
            return broadcastValue != null && broadcastValue.asBoolean();
        }

        public DotName getInputCodec() {
            return getCodec("codec");
        }

        public DotName getOutputCodec() {
            DotName output = getCodec("outputCodec");
            return output != null ? output : getInputCodec();
        }

        private DotName getCodec(String valueName) {
            AnnotationValue codecValue = annotation.value(valueName);
            if (codecValue != null) {
                return codecValue.asClass().name();
            }
            return null;
        }

        public enum MessageType {
            NONE,
            PONG,
            TEXT,
            BINARY
        }

        public List<CallbackArgument> messageArguments() {
            if (arguments.isEmpty()) {
                return List.of();
            }
            List<CallbackArgument> ret = new ArrayList<>();
            for (CallbackArgument arg : arguments) {
                if (arg instanceof MessageCallbackArgument) {
                    ret.add(arg);
                }
            }
            return ret;
        }

        public ResultHandle[] generateArguments(BytecodeCreator bytecode,
                TransformedAnnotationsBuildItem transformedAnnotations, String endpointPath) {
            if (arguments.isEmpty()) {
                return new ResultHandle[] {};
            }
            ResultHandle[] resultHandles = new ResultHandle[arguments.size()];
            int idx = 0;
            for (CallbackArgument argument : arguments) {
                resultHandles[idx] = argument.get(
                        invocationBytecodeContext(annotation, method.parameters().get(idx), transformedAnnotations,
                                endpointPath, bytecode));
                idx++;
            }
            return resultHandles;
        }

        static List<CallbackArgument> collectArguments(AnnotationInstance annotation, MethodInfo method,
                CallbackArgumentsBuildItem callbackArguments, TransformedAnnotationsBuildItem transformedAnnotations,
                String endpointPath) {
            List<MethodParameterInfo> parameters = method.parameters();
            if (parameters.isEmpty()) {
                return List.of();
            }
            List<CallbackArgument> arguments = new ArrayList<>(parameters.size());
            for (MethodParameterInfo parameter : parameters) {
                List<CallbackArgument> found = callbackArguments
                        .findMatching(parameterContext(annotation, parameter, transformedAnnotations, endpointPath));
                if (found.isEmpty()) {
                    String msg = String.format("Unable to inject @%s callback parameter '%s' declared on %s: no injector found",
                            DotNames.simpleName(annotation.name()),
                            parameter.name() != null ? parameter.name() : "#" + parameter.position(),
                            WebSocketServerProcessor.callbackToString(method));
                    throw new WebSocketServerException(msg);
                } else if (found.size() > 1 && (found.get(0).priotity() == found.get(1).priotity())) {
                    String msg = String.format(
                            "Unable to inject @%s callback parameter '%s' declared on %s: ambiguous injectors found: %s",
                            DotNames.simpleName(annotation.name()),
                            parameter.name() != null ? parameter.name() : "#" + parameter.position(),
                            WebSocketServerProcessor.callbackToString(method),
                            found.stream().map(p -> p.getClass().getSimpleName() + ":" + p.priotity()));
                    throw new WebSocketServerException(msg);
                }
                arguments.add(found.get(0));
            }
            return arguments;
        }

        static ParameterContext parameterContext(AnnotationInstance callbackAnnotation, MethodParameterInfo parameter,
                TransformedAnnotationsBuildItem transformedAnnotations, String endpointPath) {
            return new ParameterContext() {

                @Override
                public MethodParameterInfo parameter() {
                    return parameter;
                }

                @Override
                public Set<AnnotationInstance> parameterAnnotations() {
                    return Annotations.getParameterAnnotations(
                            transformedAnnotations::getAnnotations, parameter.method(), parameter.position());
                }

                @Override
                public AnnotationInstance callbackAnnotation() {
                    return callbackAnnotation;
                }

                @Override
                public String endpointPath() {
                    return endpointPath;
                }

            };
        }

        private InvocationBytecodeContext invocationBytecodeContext(AnnotationInstance callbackAnnotation,
                MethodParameterInfo parameter, TransformedAnnotationsBuildItem transformedAnnotations, String endpointPath,
                BytecodeCreator bytecode) {
            return new InvocationBytecodeContext() {

                @Override
                public AnnotationInstance callbackAnnotation() {
                    return callbackAnnotation;
                }

                @Override
                public MethodParameterInfo parameter() {
                    return parameter;
                }

                @Override
                public Set<AnnotationInstance> parameterAnnotations() {
                    return Annotations.getParameterAnnotations(
                            transformedAnnotations::getAnnotations, parameter.method(), parameter.position());
                }

                @Override
                public String endpointPath() {
                    return endpointPath;
                }

                @Override
                public BytecodeCreator bytecode() {
                    return bytecode;
                }

                @Override
                public ResultHandle getMessage() {
                    return acceptsMessage() ? bytecode.getMethodParam(0) : null;
                }

                @Override
                public ResultHandle getDecodedMessage(Type parameterType) {
                    return acceptsMessage()
                            ? WebSocketServerProcessor.decodeMessage(bytecode, acceptsBinaryMessage(), parameterType,
                                    getMessage(), Callback.this)
                            : null;
                }

                @Override
                public ResultHandle getConnection() {
                    return bytecode.readInstanceField(
                            FieldDescriptor.of(WebSocketEndpointBase.class, "connection", WebSocketConnection.class),
                            bytecode.getThis());
                }
            };
        }

    }

}
