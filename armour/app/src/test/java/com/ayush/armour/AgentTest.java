package com.ayush.armour;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AgentTest {
    @Test public void sparseTreeRecoversWithinAndAcrossRuns() throws Exception {
        Context context = mock(Context.class);
        Agent.El element = mock(Agent.El.class);
        element.id = "e0";
        when(element.describe()).thenReturn("Play");
        List<Agent.El> usable = Arrays.asList(element, element);
        List<String> modes = new ArrayList<>();
        try (MockedStatic<Injector> injector = mockStatic(Injector.class);
             MockedStatic<Prefs> prefs = mockStatic(Prefs.class);
             MockedStatic<Ai> ai = mockStatic(Ai.class);
             MockedStatic<Base64> base64 = mockStatic(Base64.class);
             MockedStatic<Log> log = mockStatic(Log.class);
             MockedStatic<Agent> agent = mockStatic(Agent.class, CALLS_REAL_METHODS)) {
            prefs.when(() -> Prefs.typesafeKey(context)).thenReturn("");
            prefs.when(() -> Prefs.apiKey(context)).thenReturn("");
            injector.when(() -> Injector.query(context, "shot")).thenReturn(new byte[]{1});
            agent.when(() -> Agent.front(context)).thenReturn("");
            agent.when(() -> Agent.elements(context)).thenReturn(Collections.emptyList(), usable, Collections.emptyList(), usable);
            ai.when(() -> Ai.json(eq(context), anyString(), anyString(), anyString(), nullable(String.class), anyInt()))
                .thenAnswer(call -> {
                    boolean screenshot = call.getArgument(4) != null;
                    modes.add(screenshot ? "screenshot" : "tree");
                    // A nonexistent selection continues immediately to the next observation.
                    return new JSONObject().put("action", modes.size() == 1 ? "select" : "done").put("id", "missing");
                });
            assertTrue(Agent.run(context, "goal", "test.app", message -> {}, false));
            assertTrue(Agent.run(context, "goal", "test.app", message -> {}, false));
            assertTrue(Agent.run(context, "goal", "test.app", message -> {}, false));
            assertEquals(Arrays.asList("screenshot", "tree", "screenshot", "tree"), modes);
            agent.verify(() -> Agent.elements(context), times(4));
        }
    }
}
