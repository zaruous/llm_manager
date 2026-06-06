/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.ui.controller.AddServiceController;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Optional;

public class AddServiceDialog {
    private final Stage owner;
    private final ServiceDefinition prefill;

    public AddServiceDialog(Stage owner) {
        this(owner, null);
    }

    public AddServiceDialog(Stage owner, ServiceDefinition prefill) {
        this.owner = owner;
        this.prefill = prefill;
    }

    public Optional<ServiceDefinition> showAndWait() {
        // builtin 서비스는 최적화 다이얼로그로 라우팅
        if (prefill != null && prefill.isBuiltin()) {
            return new BuiltinServiceSetupDialog(owner, prefill).showAndWait();
        }

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/kyj/llmmanager/add-service.fxml"));
            Parent root = loader.load();
            AddServiceController controller = loader.getController();

            if (prefill != null) {
                controller.prefill(prefill);
            }

            Stage stage = new Stage();
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("서비스 추가");
            stage.setScene(SceneFactory.create(root, 760));  // 너비 고정, 높이 자동
            stage.setMinWidth(680);
            stage.setMinHeight(520);
            stage.setResizable(true);
            SceneFactory.autoHeight(stage);
            stage.showAndWait();

            return Optional.ofNullable(controller.getResult());
        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }
}
