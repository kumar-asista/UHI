package in.gov.abdm.uhi.hspa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.gov.abdm.uhi.common.dto.Error;
import in.gov.abdm.uhi.common.dto.*;
import in.gov.abdm.uhi.hspa.models.IntermediateAppointmentModel;
import in.gov.abdm.uhi.hspa.models.IntermediateAppointmentSearchModel;
import in.gov.abdm.uhi.hspa.models.IntermediateProviderAppointmentModel;
import in.gov.abdm.uhi.hspa.service.ServiceInterface.IService;
import in.gov.abdm.uhi.hspa.utils.IntermediateBuilderUtils;
import in.gov.abdm.uhi.hspa.utils.ProtocolBuilderUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class SelectService implements IService {

    @Value("${spring.openmrs_baselink}")
    String OPENMRS_BASE_LINK;

    @Value("${spring.openmrs_api}")
    String OPENMRS_API;

    @Value("${spring.openmrs_username}")
    String OPENMRS_USERNAME;

    @Value("${spring.openmrs_password}")
    String OPENMRS_PASSWORD;

    @Value("${spring.provider_uri}")
    String PROVIDER_URI;

    private static final String API_RESOURCE_PROVIDER = "provider";
    private static final String API_RESOURCE_APPOINTMENTSCHEDULING_TIMESLOT = "appointmentscheduling/timeslot?limit=100";

    private static final String API_RESOURCE_APPOINTMENT_TYPE = "appointmentscheduling/appointmenttype?q=consultation";

    @Autowired
    WebClient webClient;

    private static final Logger LOGGER = LogManager.getLogger(WrapperService.class);

    public Mono<Response> processor(@RequestBody String request) {

        LOGGER.info("Processing::Search(Select)::Request::" + request);
        System.out.println("Processing::Search(Select))::Request::" + request);

        Request objRequest;
        Response ack = generateAck();

        try {
            objRequest = new ObjectMapper().readValue(request, Request.class);
            Request finalObjRequest = objRequest;
            System.out.println(finalObjRequest);
            run(finalObjRequest).zipWith(getAllAppointmentTypes())
                    .flatMap(pair -> getProviderAppointment(pair, finalObjRequest))
                    .flatMap(res -> getProviderAppointments(res, finalObjRequest))
                    .flatMap(this::transformObject)
                    .flatMap(collection -> generateCatalog(collection))
                    .flatMap(catalog -> callOnSerach(catalog, finalObjRequest.getContext()))
                    .flatMap(this::logResponse)
                    .subscribe();

        } catch (Exception ex) {
            LOGGER.error("Search(Select) service processor::error::onErrorResume::" + ex);
            ack = generateNack(ex);
        }

        Mono<Response> responseMono = Mono.just(ack);
        return responseMono;
    }


    @Override
    public Mono<String> run(Request request) {

            String searchEndPoint = OPENMRS_BASE_LINK + OPENMRS_API + API_RESOURCE_PROVIDER;

            Map<String, String> searchParams = IntermediateBuilderUtils.BuildSearchParametersIntent(request);
            String searchString = buildSearchString(searchParams);
            return webClient.get()
                    .uri(searchEndPoint + searchString)
                    .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class));
    }

    public Mono<String> getAllAppointmentTypes() {

        String searchEndPoint = OPENMRS_BASE_LINK + OPENMRS_API + API_RESOURCE_APPOINTMENT_TYPE;
        return webClient.get()
                .uri(searchEndPoint)
                .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class));
    }

    private Mono<IntermediateAppointmentSearchModel> getProviderAppointment(Tuple2 result, Request request) {

        LOGGER.info("Processing::Search(Select)::getProviderAppointment::" + result);
        System.out.println("Processing::Search(Select)::getProviderAppointment::" + result);

        IntermediateAppointmentSearchModel appointmentSearchModel = new IntermediateAppointmentSearchModel();
        appointmentSearchModel.providers = new ArrayList<>();
        appointmentSearchModel.appointmentTypes = new ArrayList<>();


        try {

            Map<String, String> searchParams = IntermediateBuilderUtils.BuildSearchParametersIntent(request);
            appointmentSearchModel.providers  = IntermediateBuilderUtils.BuildIntermediateProviderDetails(result.getT1().toString());
            appointmentSearchModel.appointmentTypes = IntermediateBuilderUtils.BuildIntermediateAppointment(result.getT2().toString());
            appointmentSearchModel.startDate = searchParams.get("fromDate");
            appointmentSearchModel.endDate = searchParams.get("toDate");
            appointmentSearchModel.view = "custom:uuid,startDate,endDate,appointmentBlock:" +
                                          "(provider:(uuid,display,identifier,attributes,person:(age,gender)))";

        } catch (Exception ex) {
            LOGGER.error("Search(Select) service Get Provider Id::error::onErrorResume::" + ex);
        }


        return Mono.just(appointmentSearchModel);
    }

    Mono<String> getProviderAppointments(IntermediateAppointmentSearchModel data, Request request) {


        if (data.providers.size() > 0) {

            String appointmentType = request.getMessage().getIntent().getFulfillment().getType();


            String searchEndPoint = OPENMRS_BASE_LINK + OPENMRS_API + API_RESOURCE_APPOINTMENTSCHEDULING_TIMESLOT;

            List<IntermediateAppointmentModel> appointmentTypeList = data.getAppointmentTypes().stream().filter(res -> res.getAppointmentTypeDisplay().equalsIgnoreCase(appointmentType)).collect(Collectors.toList());

            String provider = data.getProviders().get(0).getId();
            String appointment = appointmentTypeList.get(0).getAppointmentTypeUUID();//"b7f07cc1-1147-48b7-8585-0df6bd15f606";//
            String startDate = data.getStartDate();
            String endDate = data.getEndDate();

            String filterProvider = "&provider=" + provider;
            String filterAppointmentType = "&appointmentType=" + appointment;
            String filterStartDate = "&fromDate=" + startDate;
            String filterEndDate = "&toDate=" + endDate;
            String view = "&v=" + data.getView();

            final String uri = searchEndPoint + filterProvider + filterAppointmentType + filterStartDate + filterEndDate + view;
            final Mono<String> response = webClient.get()
                    .uri(uri)
                    .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class));

            return response;


        } else {
            return Mono.empty();
        }
    }

       private Mono<List<IntermediateProviderAppointmentModel>> transformObject(String result) {

        LOGGER.info("Processing::Search(Select)::transformObject::" + result);
        System.out.println("Processing::Search(Select)::transformObject::" + result);

        List<IntermediateProviderAppointmentModel> collection = new ArrayList<>();
        try {

            collection = IntermediateBuilderUtils.BuildIntermediateProviderAppoitmentObj(result);

        } catch (Exception ex) {
            LOGGER.error("Select service Transform Object::error::onErrorResume::" + ex);
        }
        return Mono.just(collection);

    }

     private Mono<Catalog> generateCatalog(List<IntermediateProviderAppointmentModel> collection) {

        Catalog catalog = new Catalog();
        List<IntermediateProviderAppointmentModel> filteredList = collection;
        try {
            catalog = ProtocolBuilderUtils.BuildProviderCatalog(filteredList);

        } catch (Exception ex) {

            LOGGER.error("Select service generate catalog::error::onErrorResume::" + ex);
        }
        return Mono.just(catalog);

    }

    private Mono<String> callOnSerach(Catalog catalog, Context context) {
        Request onSearchRequest = new Request();
        Message objMessage = new Message();
        objMessage.setCatalog(catalog);
        context.setProviderId(PROVIDER_URI);
        context.setProviderUri(PROVIDER_URI);
        context.setAction("on_search");
        onSearchRequest.setMessage(objMessage);
        onSearchRequest.setContext(context);
        onSearchRequest.getContext().setAction("on_search");

        System.out.println(onSearchRequest);

        WebClient on_webclient = WebClient.create();

        return on_webclient.post()
                .uri(context.getConsumerUri() + "/on_search")
                .body(BodyInserters.fromValue(onSearchRequest))
                .retrieve()
                .bodyToMono(String.class)
                .retry(3)
                .onErrorResume(error -> {
                    LOGGER.error("Select Service Call on_search::error::onErrorResume::" + error);
                    return Mono.empty();
                });
    }

    public static Response generateAck() {

        MessageAck msz = new MessageAck();
        Response res = new Response();
        Ack ack = new Ack();
        ack.setStatus("ACK");
        msz.setAck(ack);
        Error err = new Error();
        res.setError(err);
        res.setMessage(msz);
        return res;
    }

    private static Response generateNack(Exception js) {

        MessageAck msz = new MessageAck();
        Response res = new Response();
        Ack ack = new Ack();
        ack.setStatus("NACK");
        msz.setAck(ack);
        Error err = new Error();
        err.setMessage(js.getMessage());
        err.setType("Select");
        res.setError(err);
        res.setMessage(msz);
        return res;
    }

    private String buildSearchString(Map<String, String> params) {
        String searchString = "?v=custom:uuid,providerId,identifier,person:(display)&q=";
        String value = "";
        if (params.get("hprid") == null || Objects.equals(params.get("hprid"), "")) {
            value = params.getOrDefault("name", "");
        } else {
            value = params.getOrDefault("hprid", "");
        }
        return searchString + value;
    }

    @Override
    public Mono<String> logResponse(java.lang.String result) {

        LOGGER.info("OnSearch(Select)::Log::Response::" + result);
        System.out.println("OnSearch(Select)::Log::Response::" + result);

        return Mono.just(result);
    }
}
