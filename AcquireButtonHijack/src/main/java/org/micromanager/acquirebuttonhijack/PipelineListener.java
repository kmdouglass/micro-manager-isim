package org.micromanager.acquirebuttonhijack;

import com.google.common.eventbus.Subscribe;
import mmcorej.org.json.JSONException;
import mmcorej.org.json.JSONObject;
import org.micromanager.PluginManager;
import org.micromanager.Studio;
import org.micromanager.acquisition.AcquisitionEndedEvent;
import org.micromanager.acquisition.SequenceSettings;
import org.micromanager.data.*;
import org.micromanager.data.internal.CommentsHelper;
import org.micromanager.data.internal.DefaultSummaryMetadata;
import org.micromanager.display.DisplayWindow;
import org.micromanager.internal.MMStudio;
import org.micromanager.internal.interfaces.AcqSettingsListener;
import org.micromanager.internal.propertymap.NonPropertyMapJSONFormats;
import org.micromanager.internal.utils.JavaUtils;
import org.micromanager.internal.utils.MDUtils;

import java.io.File;
import java.io.IOException;




public class PipelineListener extends Thread implements AcqSettingsListener {

    private static SequenceSettings settings_;
    private static int acqNumber_;
    private Studio studio_;
    private DataManager dataManager;
    private PluginManager pluginManager_;
    private AcquireButtonHijackFrame acq_hijack_;

    public PipelineListener(Studio studio, AcquireButtonHijackFrame acq_hijack){
        studio_ = studio;
        dataManager = studio_.data();
        pluginManager_ = studio_.plugins();
        acq_hijack_ = acq_hijack;

    }

    public void init(){
        studio_.events().registerForEvents(this);
        settings_ = studio_.acquisitions().getAcquisitionSettings();
    }


    public static void storeSettings(SequenceSettings newSettings, int newAcqNumber_){
        settings_ = newSettings;
        acqNumber_ = newAcqNumber_;
    }

    @Subscribe
    public void onPipelineChanged(NewPipelineEvent event) {
        System.out.println("Pipeline Changed!");
        // Configure the PseudoChannel Processor
        boolean pseudoChannelsSet = false;
        for (int i=0; i<dataManager.getApplicationPipelineConfigurators(true).size(); i++){
            ProcessorConfigurator plugin = dataManager.getApplicationPipelineConfigurators(true).get(i);
            String pluginName = plugin.getClass().getName();
            String[] names = pluginName.split("\\.");
            if (names[names.length - 1].equals("PseudoChannelConfigurator")){
                acq_hijack_.setPseudoChannels(plugin);
                pseudoChannelsSet = true;
                System.out.println("PseudoChannels Found");
                break;
            }
        }

        if (!pseudoChannelsSet) {
            dataManager.addAndConfigureProcessor(pluginManager_.getProcessorPlugins()
                    .get("org.micromanager.pseudochannels.PseudoChannelPlugin"));
            acq_hijack_.setPseudoChannels(dataManager.getApplicationPipelineConfigurators(true)
                    .get(dataManager.getApplicationPipelineConfigurators(true).size() - 1));
        }
    }

    @Subscribe
    public void onAcquisitionEnded(AcquisitionEndedEvent event) throws IOException{


        int numSlices = Math.max(1,settings_.slices().size());
        // How many channels are actually active?
        int numChannels = 0;
        for (int i=0; i<settings_.channels().size(); i++){
            numChannels += settings_.channels().get(i).useChannel() ? 1 : 0;
        }

        RewritableDatastore my_store = studio_.data().createRewritableRAMDatastore();

        JSONObject summaryMetadata = ((MMStudio) studio_).getAcquisitionEngine2010().getSummaryMetadata();
        SummaryMetadata summary = null;
        try {
            CommentsHelper.setSummaryComment(my_store, MDUtils.getComments(summaryMetadata));
            summaryMetadata.put("Slices", numSlices);
            summaryMetadata.put("Channels", numChannels);
            summaryMetadata.put("Frames", settings_.numFrames());
            summary = DefaultSummaryMetadata.fromPropertyMap(
                    NonPropertyMapJSONFormats.summaryMetadata().fromJSON(
                            summaryMetadata.toString()));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        my_store.setSummaryMetadata(summary);

        // Transfer all images from the original Datastore to this one.
        Datastore store = event.getStore();
        Iterable<Coords> coords = store.getUnorderedImageCoords();
        for (Coords coord: coords){
            Image new_image= store.getImage(coord);
            my_store.putImage(new_image);
        }

        String fileName = settings_.prefix() + "_" + acqNumber_;
        String acqPath = settings_.root() + File.separator + fileName;

        //Save the store with the correct summary metadata
        if (settings_.save()) {
            try {
                System.out.println(acqPath);
                JavaUtils.createDirectory(settings_.root());
                my_store.save(Datastore.SaveMode.MULTIPAGE_TIFF, acqPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Close the original acquisition window and open one with the new data
        DisplayWindow display = studio_.displays().getDisplays(event.getStore()).get(0);
        studio_.displays().removeViewer(display);
        my_store.setName(fileName);
        studio_.displays().createDisplay(my_store);
        display.close();
    }

    @Override
    public void settingsChanged() {
    }


}