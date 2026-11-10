package com.fleetops.mockamr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ArrivalReporter {

    private final HttpClient client = HttpClient.newHttpClient();

    public void reportArrival(Long robotId, Long taskId)
            throws Exception {

        String url = "http://localhost:8080/api/robots/"
                + robotId + "/tasks/" + taskId + "/arrival";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> response = client.send(
                request,
                HttpResponse.BodyHandlers.discarding()
        );

        if (response.statusCode() != 204) {
            throw new IllegalStateException(
                    "도착 보고 실패: HTTP " + response.statusCode()
            );
        }
    }
}