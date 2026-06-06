/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.ui.controller.BuiltinServiceSetupController;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Optional;

/**
 * builtin 서비스 전용 최적화 설정 다이얼로그.
 * 일반 AddServiceDialog 대신 사용되며, 활성/비활성 인수 분리·그룹 섹션·Groovy 자동 적용을 제공한다.
 */
public class BuiltinServiceSetupDialog {

    private final Stage owner;
    /** 설정 대상 builtin 서비스 정의 */
    private final ServiceDefinition def;

    /**
     * @param owner 오너 Stage
     * @param def   설정할 builtin ServiceDefinition
     */
    public BuiltinServiceSetupDialog(Stage owner, ServiceDefinition def) {
        this.owner = owner;
        this.def   = def;
    }

    /**
     * 다이얼로그를 표시하고 사용자 입력 결과를 반환한다.
     *
     * @return 추가 완료된 ServiceDefinition, 취소 시 empty
     */
    public Optional<ServiceDefinition> showAndWait() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/kyj/llmmanager/builtin-service-setup.fxml"));
            Parent root = loader.load();
            BuiltinServiceSetupController controller = loader.getController();
            controller.setup(def);

            Stage stage = new Stage();
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle(def.getName() + " — 서비스 설정");
            stage.setScene(SceneFactory.create(root, 860, 640));
            stage.setMinWidth(760);
            stage.setMinHeight(560);
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
