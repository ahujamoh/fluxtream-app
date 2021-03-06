package org.fluxtream.core.api;

import com.google.gson.Gson;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import net.sf.json.JSONObject;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.fluxtream.core.Configuration;
import org.fluxtream.core.auth.AuthHelper;
import org.fluxtream.core.auth.TrustRelationshipRevokedException;
import org.fluxtream.core.connectors.Connector;
import org.fluxtream.core.domain.ApiKey;
import org.fluxtream.core.domain.TrustedBuddy;
import org.fluxtream.core.domain.Guest;
import org.fluxtream.core.mvc.models.StatusModel;
import org.fluxtream.core.mvc.models.guest.GuestModel;
import org.fluxtream.core.services.BuddiesService;
import org.fluxtream.core.services.GuestService;
import org.fluxtream.core.utils.HttpUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.fluxtream.core.utils.Utils.hash;

@Path("/guest")
@Component("RESTLegacyGuestController")
@Api(value = "/guest", description = "Retrieve guest information")
@Scope("request")
@Deprecated
public class LegacyGuestController {

	@Autowired
	GuestService guestService;

	Gson gson = new Gson();

	@Autowired
	Configuration env;

    @Autowired
    BuddiesService buddiesService;

    @GET
	@Path("/")
	@Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(value = "Retrieve information on the currently logged in's guest", response = GuestModel.class)
    @Deprecated
	public Object getCurrentGuest() throws InstantiationException,
			IllegalAccessException, ClassNotFoundException {
        try{
            long guestId = AuthHelper.getGuestId();

            Guest guest = guestService.getGuestById(guestId);
            GuestModel guestModel = new GuestModel(guest, false);

            return guestModel;
        }
        catch (Exception e){
            return new StatusModel(false,"Failed to get current guest: " + e.getMessage());
        }
	}

    @GET
    @Path("/avatarImage/{buddyToAccess}")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "Retrieve the avatar (gravatar) of the currently logged in's guest", response = String.class)
    @Deprecated
    public String getAvatarImage(@PathParam("buddyToAccess") String buddyToAccess) {
        Guest guest = AuthHelper.getGuest();
        JSONObject json = new JSONObject();
        String type = "none";
        String url;
        try {
            final TrustedBuddy trustedBuddy = AuthHelper.getBuddyTrustedBuddy(buddyToAccess, buddiesService);
            if (trustedBuddy !=null)
                guest = guestService.getGuestById(trustedBuddy.guestId);
        }
        catch (TrustRelationshipRevokedException e) {
            // TODO: do something about this
        }
        if (guest.registrationMethod == Guest.RegistrationMethod.REGISTRATION_METHOD_FACEBOOK||
            guest.registrationMethod == Guest.RegistrationMethod.REGISTRATION_METHOD_FACEBOOK_WITH_PASSWORD) {
            url = getFacebookAvatarImageURL(guest);
            if (url!=null)
                type = "facebook";
        } else {
            url = getGravatarImageURL(guest);
            if (url!=null)
                type = "gravatar";
        }
        json.put("type", type);
        json.put("url", url);
        final String jsonString = json.toString();
        return jsonString;
    }

    private String getGravatarImageURL(Guest guest) {
        String emailHash = hash(guest.email.toLowerCase().trim()); //gravatar specifies the email should be trimmed, taken to lowercase, and then MD5 hashed
        String gravatarURL = String.format("http://www.gravatar.com/avatar/%s?s=27&d=retro", emailHash);
        HttpGet get = new HttpGet(gravatarURL);
        int res = 0;
        try { res = ((new DefaultHttpClient()).execute(get)).getStatusLine().getStatusCode(); }
        catch (IOException e) {e.printStackTrace();}
        return res==200 ? gravatarURL : null;
    }

    public String getFacebookAvatarImageURL(Guest guest) {
        final ApiKey facebook = guestService.getApiKey(guest.getId(), Connector.getConnector("facebook"));
        final String meString = guestService.getApiKeyAttribute(facebook, "me");
        JSONObject meJSON = JSONObject.fromObject(meString);
        final String facebookId = meJSON.getString("id");
        try {
            String avatarURL = String.format("http://graph.facebook.com/%s/picture?type=small&redirect=false&return_ssl_resources=true", facebookId);
            String jsonString = HttpUtils.fetch(avatarURL);
            JSONObject json = JSONObject.fromObject(jsonString);
            if (json.has("data")) {
                json = json.getJSONObject("data");
                if (json.has("url")&&json.has("is_silhouette")) {
                    if (!json.getBoolean("is_silhouette")) {
                        return json.getString("url");
                    }
                }
            }
        } catch (Throwable t){
            t.printStackTrace();
        }
        return null;
    }

    @GET
    @Path("/trustingBuddies")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "Retrieve the currently logged in guest's list of trustingBuddies", responseContainer = "Array",
            response = GuestModel.class)
    @Deprecated
    public List<GuestModel> getTrustingBuddies() {
        Guest guest = AuthHelper.getGuest();
        final List<Guest> trustingBuddies = buddiesService.getTrustedBuddies(guest.getId());
        final List<GuestModel> trustingBuddyModels = new ArrayList<GuestModel>();
        for (Guest trustingBuddy : trustingBuddies)
            trustingBuddyModels.add(new GuestModel(trustingBuddy, true));
        return trustingBuddyModels;
    }

}
