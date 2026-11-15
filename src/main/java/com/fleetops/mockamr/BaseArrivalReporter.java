package com.fleetops.mockamr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class BaseArrivalReporter{

    private final HttpClient client = HttpClient.newHttpClient();

    private final String baseUrl;

    public BaseArrivalReporter(String baseUrl){
        this.baseUrl = baseUrl;
    }

    public void reportArrival(Long robotId)
            throws Exception{
            
        String url = baseUrl + "/api/robots/" + robotId
            + "/arrival/base";
            
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        
        HttpResponse<Void> response = client.send(
             request,
            HttpResponse.BodyHandlers.discarding()
        );

        if(response.statusCode() != 204){
            throw new IllegalStateException(
                "도착 보고 실패: HTTP " + response.statusCode()
            );
        }
    }
}