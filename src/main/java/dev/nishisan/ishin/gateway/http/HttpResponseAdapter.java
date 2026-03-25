/*
 * Copyright (C) 2023 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.nishisan.ishin.gateway.http;

import dev.nishisan.ishin.gateway.http.synth.response.SyntHttpResponse;
import dev.nishisan.ishin.gateway.observability.wrappers.SpanWrapper;
import dev.nishisan.ishin.gateway.observability.wrappers.TracerWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 19.01.2023
 */
public class HttpResponseAdapter {

    private final Logger logger = LogManager.getLogger(HttpResponseAdapter.class);

    public void writeResponse(HttpWorkLoad w) throws IOException {
        TracerWrapper tracer = w.getContext().getTracerWrapper();

        SpanWrapper responseSpan = tracer.createSpan("response-adapter");
        Long start = System.currentTimeMillis();
        try {
            //
            // --- Fase 1: Setup do client response ---
            //
            SpanWrapper setupSpan = tracer.createChildSpan("response-setup", responseSpan);
            boolean synth = false;
            try {
                if (w.getClientResponse() == null) {
                    logger.debug("Using Client Response");
                    w.setClientResponse(w.getContext().res());
                } else {
                    logger.warn("Not Using Client Response");
                    synth = true;
                }
                w.getClientResponse().addHeader("x-trace-id", tracer.getTraceId());
            } finally {
                setupSpan.tag("synth", "" + synth);
                setupSpan.finish();
            }

            //
            // --- Fase 2 + 3: Response Processors + Decisão de pipe ---
            //
            boolean returnPipe = false;
            SpanWrapper decisionSpan = tracer.createChildSpan("response-pipe-decision", responseSpan);
            try {
                if (!w.getResponseProcessors().isEmpty()) {
                    w.getResponseProcessors().forEach((p, c) -> {
                        SpanWrapper processorSpan = tracer.createChildSpan("response-processor", decisionSpan);
                        processorSpan.tag("processor-name", p);
                        try {
                            logger.debug("Running Processor:[{}]", p);
                            if (c.getMaximumNumberOfParameters() == 1) {
                                c.call(w);
                            } else {
                                logger.warn("Invalid Processor Discarted:[{}]", p);
                            }
                        } finally {
                            processorSpan.finish();
                        }
                    });
                }

                logger.debug("getReturnPipe():[{}]", w.getReturnPipe());
                if (w.getReturnPipe() && !synth) {
                    if (w.getClientResponse().getSynthResponse() == null) {
                        if (!w.getUpstreamResponse().getWasRead()) {
                            returnPipe = true;
                        } else {
                            logger.info("Content Was Touched, cant return pipe anymore..");
                        }
                    } else {
                        logger.debug("Synth Response is present");
                    }
                }
                logger.debug("Return Pipe is :[{}]", returnPipe);
            } finally {
                decisionSpan.tag("return-pipe", "" + returnPipe);
                decisionSpan.tag("has-processors", "" + !w.getResponseProcessors().isEmpty());
                decisionSpan.finish();
            }

            responseSpan.tag("return-pipe", "" + returnPipe);
            if (returnPipe) {
                //
                // --- Fase 4: Cópia de headers do upstream ---
                //
                SpanWrapper headersSpan = tracer.createChildSpan("response-headers-copy", responseSpan);
                try {
                    w.getClientResponse().setStatus(w.getUpstreamResponse().getStatus());
                    headersSpan.tag("status-code", "" + w.getUpstreamResponse().getStatus());
                    w.getUpstreamResponse().getHeaderNames().forEach(header -> {
                        w.getClientResponse().addHeader(header, w.getUpstreamResponse().getHeader(header));
//                        if (logger.isDebugEnabled()) {
//                            logger.debug("Upstream Response Headers: [{}]:={}", header, w.getUpstreamResponse().getHeader(header));
//                        }
                    });
                    headersSpan.tag("upstream-headers-count", "" + w.getUpstreamResponse().getHeaderNames().size());
                } finally {
                    headersSpan.finish();
                }

                //
                // --- Fase 5: Pipe de streaming ---
                //
                SpanWrapper clientSpan = tracer.createChildSpan("response-send-to-client", responseSpan);

                // Detecta SSE pelo Content-Type do upstream ou flag explícita do workload
                String upstreamContentType = w.getUpstreamResponse().getContentType();
                if (upstreamContentType == null) {
                    upstreamContentType = w.getUpstreamResponse().getHeader("Content-Type");
                }
                boolean isSse = w.isSseMode()
                        || (upstreamContentType != null && upstreamContentType.contains("text/event-stream"));
                clientSpan.tag("sse", String.valueOf(isSse));

                if (isSse) {
                    writeSseStream(w, clientSpan);
                } else {
                    try (InputStream inputStream = w.getUpstreamResponse().getOkHttpResponse().body().byteStream(); OutputStream outputStream = w.getClientResponse().getOutputStream()) {
                        logger.debug("Returning Pipe to the Client....");

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                }
                clientSpan.finish();
            } else {
                responseSpan.tag("return-synth", "" + synth);
                if (synth) {
                    //
                    // Houve uma resposta sintetica no script
                    //
                    SyntHttpResponse res = w.getClientResponse().getSynthResponse();

                    if (res != null) {
                        w.getContext().status(res.getStatus());
                        responseSpan.tag("status-code", "" + res.getStatus());
                        logger.debug("Response Code:[{}]", res.getStatus());
                        //
                        // Copy Headers
                        //
                        res.getHeaderNames().forEach(name -> {
                            logger.debug("Upstream Response Headers: [{}]:={}", name, res.getHeader(name));
                            w.getContext().header(name, res.getHeader(name));
                            responseSpan.tag("header-" + name, "" + res.getHeader(name));
                        });

                    }
                    if (res.getContentType() != null) {
                        w.getContext().contentType(res.getContentType());
                    }

                    /**
                     * Manda direto sem precisar de tratamento
                     */
                    if (res != null) {
                        byte[] resBody = res.getContentAsByteArray();
//                        logger.debug("Returning Response Body  :[{}]=({})", new String(resBody), resBody.length);
                        if (resBody.length > 0) {
                            w.getClientResponse().addHeader("Content-Length", String.valueOf(resBody.length));

                        }
                        SpanWrapper clientSpan = tracer.createChildSpan("response-send-to-client", responseSpan);
                        w.getContext().result(resBody);
                        clientSpan.finish();
                        //logger.debug("Content Length:[{}]", w.getContext().contentLength());
                    }

                } else {
                    //
                    // Upstream Backend Response
                    //
                    SyntHttpResponse res = w.getUpstreamResponse();
                    if (res != null) {
                        w.getClientResponse().setStatus(res.getStatus());
                        responseSpan.tag("status-code", "" + res.getStatus());
                        logger.debug("Response Code:[{}]", res.getStatus());
                        res.getHeaderNames().forEach(name -> {
//                            logger.debug("Response Headers: [{}]:={}", name, res.getHeader(name));
                            logger.debug("Upstream Response Headers: [{}]:={}", name, res.getHeader(name));
                            if (!name.equalsIgnoreCase("Content-Length")) {
                                w.getClientResponse().addHeader(name, res.getHeader(name));
                            }
                            responseSpan.tag("header-" + name, "" + res.getHeader(name));
                        });
                    }

                    if (res != null) {
                        byte[] resBody = res.getContentAsByteArray();
//                        logger.debug("Returning Response Body  :[{}]=({})", new String(resBody), resBody.length);
                        if (resBody.length > 0) {
                            w.getClientResponse().addHeader("Content-Length", String.valueOf(resBody.length));

                        }
                        SpanWrapper clientSpan = tracer.createChildSpan("response-send-to-client", responseSpan);
                        w.getContext().result(resBody);
                        clientSpan.finish();
                        //logger.debug("Content Length:[{}]", w.getContext().contentLength());
                    }

                }

                Long end = System.currentTimeMillis();
                Long took = end - start;
                logger.debug("Writing Response Done! [{}] ms", took);
            }
        } finally {
            responseSpan.finish();
        }
    }

    /**
     * SSE pass-through: lê o upstream InputStream e faz flush por evento SSE.
     * <p>
     * O delimitador de evento SSE é uma linha vazia ({@code \n\n}).
     * Cada vez que detectamos esse delimitador, fazemos flush do OutputStream
     * para garantir entrega em tempo real ao cliente.
     * <p>
     * Headers SSE obrigatórios são setados antes do streaming:
     * Content-Type: text/event-stream, Cache-Control: no-cache, Connection: keep-alive.
     */
    private void writeSseStream(HttpWorkLoad w, SpanWrapper clientSpan) throws IOException {
        logger.info("SSE pass-through: streaming events from upstream to client");

        // Setar headers SSE obrigatórios no client response
        w.getClientResponse().setStatus(w.getUpstreamResponse().getStatus());

        // Copiar headers do upstream, exceto os que serão sobrescritos
        w.getUpstreamResponse().getHeaderNames().forEach(header -> {
            String lowerHeader = header.toLowerCase();
            if (!lowerHeader.equals("content-type")
                    && !lowerHeader.equals("content-length")
                    && !lowerHeader.equals("cache-control")
                    && !lowerHeader.equals("connection")
                    && !lowerHeader.equals("transfer-encoding")) {
                w.getClientResponse().addHeader(header, w.getUpstreamResponse().getHeader(header));
            }
        });

        // Headers SSE obrigatórios
        w.getClientResponse().setContentType("text/event-stream");
        w.getClientResponse().setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        w.getClientResponse().setHeader("Connection", "keep-alive");
        w.getClientResponse().setHeader("X-Accel-Buffering", "no");

        try (InputStream inputStream = w.getUpstreamResponse().getOkHttpResponse().body().byteStream();
             OutputStream outputStream = w.getClientResponse().getOutputStream()) {

            // Flush headers before streaming data
            outputStream.flush();

            byte[] buffer = new byte[4096];
            int bytesRead;
            int consecutiveNewlines = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);

                // Detectar delimitador SSE (\n\n) e flush por evento
                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == '\n') {
                        consecutiveNewlines++;
                        if (consecutiveNewlines >= 2) {
                            // Delimitador SSE detectado — flush imediato
                            outputStream.flush();
                            consecutiveNewlines = 0;
                        }
                    } else if (buffer[i] != '\r') {
                        consecutiveNewlines = 0;
                    }
                    // \r é ignorado (suporte a \r\n)
                }
            }
            // Flush final para garantir que qualquer dado residual seja enviado
            outputStream.flush();
            logger.info("SSE pass-through: upstream stream closed normally");
        } catch (IOException ex) {
            // Client disconnect ou upstream close — não é necessariamente um erro
            logger.info("SSE pass-through: stream ended — {}", ex.getMessage());
            clientSpan.tag("sse.disconnect", ex.getClass().getSimpleName());
        }
    }
}
