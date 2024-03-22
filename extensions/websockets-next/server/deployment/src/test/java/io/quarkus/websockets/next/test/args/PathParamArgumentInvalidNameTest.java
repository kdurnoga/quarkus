package io.quarkus.websockets.next.test.args;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketServerException;

public class PathParamArgumentInvalidNameTest {

    @RegisterExtension
    public static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> {
                root.addClasses(MontyEcho.class);
            }).setExpectedException(WebSocketServerException.class);

    @Test
    void testInvalidArgument() {
        fail();
    }

    @WebSocket(path = "/echo/{grail}")
    public static class MontyEcho {

        @OnTextMessage
        String process(@PathParam String life, String message) throws InterruptedException {
            return message + ":" + life;
        }

    }

}
