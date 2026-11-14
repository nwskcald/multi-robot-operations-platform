package com.fleetops.mockamr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HandoverCompletedReporter {

    private final HttpClient client = HttpClient.newHttpClient();

    private final String baseUrl;

    public HandoverCompletedReporter(String baseUrl){
        this.baseUrl = baseUrl;
    }

    public void reportHandoverCompleted(Long robotId)
            throws Exception {

        String url = baseUrl + "/api/robots/"
                + robotId + "/handover-completion";

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
                    "자재인계 완료 보고 실패: HTTP " + response.statusCode()
            );
        }
    }
}